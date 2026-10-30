package com.singu.xfriend

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal fun followUpReminderTime(task: FollowUp, zone: ZoneId = ZoneId.systemDefault()): Long? =
    if (!task.reminder || task.done || task.dueDate.isBlank()) null else runCatching {
        LocalDate.parse(task.dueDate).atTime(LocalTime.parse(task.dueTime.ifBlank { "09:00" })).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

internal object SalesReminders {
    private fun pending(context: Context, scope: String, id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, SalesReminderReceiver::class.java).setData(Uri.parse("xfriend-reminder://$scope/$id")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun reconcile(context: Context, scope: String, data: SalesData, reschedule: Boolean = false) {
        val preferences = context.getSharedPreferences("sales-reminders-$scope", Context.MODE_PRIVATE)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val prior = preferences.all.filterValues { it is Long }.mapValues { it.value as Long }
        val next = data.followUps.mapNotNull { task -> followUpReminderTime(task)?.let { task.id to it } }.toMap()
        val edit = preferences.edit()
        for ((id, time) in prior) if (next[id] != time) { alarm.cancel(pending(context, scope, id)); edit.remove(id) }
        for ((id, time) in next) if ((reschedule || prior[id] != time) && time > System.currentTimeMillis()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(context, scope, id)); edit.putLong(id, time)
        }
        edit.apply()
    }
}

class SalesReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scope = intent.data?.host ?: return
        val id = intent.data?.lastPathSegment ?: return
        if (!scope.matches(Regex("[a-f0-9]{64}"))) return
        val result = goAsync()
        java.util.concurrent.Executors.newSingleThreadExecutor().let { executor -> executor.execute {
            try {
                val task = SalesRepository(context, scope).load().followUps.find { it.id == id } ?: return@execute
                if (!task.reminder || task.done || (followUpReminderTime(task) ?: Long.MAX_VALUE) > System.currentTimeMillis()) return@execute
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@execute
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("customer-followups", "客户跟进提醒", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                // Keep customer details off the lock screen; the account gate owns access.
                manager.notify((scope + id).hashCode(), Notification.Builder(context, "customer-followups")
                    .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("该跟进客户了")
                    .setContentText("打开 Xsalers 查看待办事项").setContentIntent(open).setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build())
            } finally { result.finish(); executor.shutdown() }
        } }
    }
}
