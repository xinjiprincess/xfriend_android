package com.singu.xfriend

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import org.json.JSONObject
import java.util.concurrent.Executors

internal object PhonePermissions {
    const val CHANNEL = "phone-personal-reminders"
    fun notificationManager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(CHANNEL, "个人闹钟与提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "星助理创建的本机事项、闹钟和定时提醒"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
    fun notifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = notificationManager(context)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun exactAlarms(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun accessibility(context: Context): Boolean {
        val component = ComponentName(context, PhoneAccessibilityService::class.java)
        return context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val info = it.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == component
            }
    }
    fun settingsIntent(context: Context, kind: String): Intent = when (kind) {
        "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        "exact_alarms" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun missing(context: Context): String? = when {
        !notifications(context) -> "notifications"
        !exactAlarms(context) -> "exact_alarms"
        else -> null
    }
}

internal object PhoneAlarms {
    private fun pending(context: Context, scopeKey: String, id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, PhoneAlarmReceiver::class.java).setData(Uri.parse("xfriend-phone://$scopeKey/$id")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun schedule(context: Context, scopeKey: String, item: JSONObject) {
        require(PhonePermissions.notifications(context)) { "请先允许通知并开启个人闹钟与提醒通知渠道。" }
        require(PhonePermissions.exactAlarms(context)) { "请先在系统设置中允许闹钟和提醒。" }
        val at = item.getLong("next_at")
        require(at > System.currentTimeMillis()) { "提醒时间已经过去。" }
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at, open(context)), pending(context, scopeKey, item.getString("id")))
    }
    fun cancel(context: Context, scopeKey: String, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, scopeKey, id))
        context.getSystemService(NotificationManager::class.java).cancel("phone:$scopeKey:$id", 1)
    }
    fun reconcileAll(context: Context) {
        PhoneRepository.accountKeys(context).forEach { key -> runCatching { reconcile(context, PhoneRepository.fromKey(context, key)) } }
    }
    fun reconcile(context: Context, repo: PhoneRepository) = synchronized(repo.lock) {
        val data = repo.read()
        val items = data.getJSONObject("items")
        val now = System.currentTimeMillis()
        items.keys().forEach { id ->
            val item = items.getJSONObject(id)
            if (item.optString("status") !in listOf("scheduled", "needs_permission") || !item.has("next_at")) return@forEach
            if (item.optString("rule") == "daily") {
                item.put("next_at", PhonePolicy.reconciledDailyTime(item.getLong("next_at"), item.getString("local_time"), item.getString("timezone"), now))
            } else if (item.getLong("next_at") <= now) {
                item.put("status", "missed"); cancel(context, repo.scopeKey, id); return@forEach
            }
            if (PhonePermissions.missing(context) != null) item.put("status", "needs_permission")
            else runCatching { schedule(context, repo.scopeKey, item) }.fold(
                onSuccess = { item.put("status", "scheduled") }, onFailure = { item.put("status", "needs_permission") })
        }
        repo.write(data)
    }
    fun deliver(context: Context, scopeKey: String, id: String) {
        val repo = PhoneRepository.fromKey(context, scopeKey)
        synchronized(repo.lock) {
            val data = repo.read()
            val item = data.getJSONObject("items").optJSONObject(id) ?: return
            if (item.optString("status") != "scheduled") return
            val now = System.currentTimeMillis()
            if (item.optLong("next_at", Long.MAX_VALUE) > now + 1_000) return
            // Persist the delivery claim before posting: a duplicate broadcast cannot post twice.
            item.put("status", "firing").put("last_fired_at", now)
            repo.write(data)
            val delivered = if (PhonePermissions.notifications(context)) runCatching {
                PhonePermissions.notificationManager(context).notify("phone:$scopeKey:$id", 1,
                    Notification.Builder(context, PhonePermissions.CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentTitle(if (item.optString("kind") == "alarm") "星助理闹钟" else "星助理提醒")
                        .setContentText("你有一项到期事项，打开星助理查看。")
                        .setContentIntent(open(context)).setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE).build())
            }.isSuccess else false
            item.put("last_delivery", if (delivered) "notification_posted" else "notifications_disabled")
            if (item.optString("rule") == "daily") {
                item.put("next_at", PhonePolicy.nextTime("daily", "", item.getString("local_time"), item.getString("timezone"), now))
                val scheduled = runCatching { schedule(context, scopeKey, item) }.isSuccess
                item.put("status", if (scheduled) "scheduled" else "needs_permission")
            } else item.put("status", if (delivered) "delivered" else "delivery_blocked")
            repo.write(data)
        }
    }
}

class PhoneAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.data?.host ?: return
        val id = intent.data?.lastPathSegment ?: return
        if (!key.matches(Regex("[a-f0-9]{64}")) || !id.matches(Regex("p-[a-f0-9]{32}"))) return
        val result = goAsync()
        executor.execute { try { runCatching { PhoneAlarms.deliver(context.applicationContext, key, id) } } finally { result.finish() } }
    }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}

class PhoneRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val result = goAsync()
        executor.execute { try { PhoneAlarms.reconcileAll(context.applicationContext) } finally { result.finish() } }
    }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
