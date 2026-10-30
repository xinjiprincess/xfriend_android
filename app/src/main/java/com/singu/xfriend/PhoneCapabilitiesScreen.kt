package com.singu.xfriend

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun PhoneCapabilitiesPage(scope: String, back: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val coroutine = rememberCoroutineScope()
    var notifications by remember { mutableStateOf(false) }
    var alarms by remember { mutableStateOf(false) }
    var accessibility by remember { mutableStateOf(false) }
    var control by remember(scope) { mutableStateOf(PhoneControlSession.isEnabled(scope)) }
    var records by remember(scope) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var message by remember(scope) { mutableStateOf("") }
    var exporting by remember(scope) { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun refresh() {
        notifications = PhonePermissions.notifications(context)
        alarms = PhonePermissions.exactAlarms(context)
        accessibility = PhonePermissions.accessibility(context)
        control = PhoneControlSession.isEnabled(scope)
        if (scope.isBlank() || !PhoneControlSession.isActive(scope)) { records = emptyList(); return }
        coroutine.launch {
            val current = withContext(Dispatchers.IO) { runCatching {
                val repo = PhoneRepository(context, scope)
                PhoneAlarms.reconcile(context, repo)
                repo.list()
            } }
            if (PhoneControlSession.isActive(scope)) current.fold(onSuccess = { records = it }, onFailure = { message = "本机记录暂时无法读取，原数据已保留。" })
        }
    }
    fun settings(kind: String) {
        runCatching { context.startActivity(PhonePermissions.settingsIntent(context, kind)) }
            .onFailure { message = "此手机没有提供对应设置入口，请从系统设置中手动开启。" }
    }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message = if (granted) "已允许通知。" else "通知尚未允许；可在系统设置中开启。"
        refresh()
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val selected = exporting
        exporting = null
        if (uri != null && selected != null) {
            if (!PhoneControlSession.isActive(selected.first) || selected.first != scope) message = "账户已切换，未导出原账户文件。"
            else coroutine.launch {
                busy = true
                val success = withContext(Dispatchers.IO) { runCatching {
                    check(PhoneControlSession.isActive(selected.first))
                    PhoneRepository(context, selected.first).export(context, selected.second, uri)
                }.isSuccess }
                message = if (success) "文件已导出到你选择的位置。" else "未确认导出完成，请检查所选位置后重试。"
                busy = false
            }
        }
    }
    LaunchedEffect(scope) { refresh() }
    DisposableEffect(lifecycle, scope) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize()) {
        XfTopBar(title = "手机能力", onBack = back)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(XfDimens.pagePadding), verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap)) {
            item {
                XfCard {
                    Text("把事情真正留在手机里", style = XfType.h3, color = xf.ink)
                    Text("普通请求默认可以执行：保存文件、创建带目标日期或提醒时刻的个人事项，并安排一次或每天的闹钟通知。只有明确“只讨论/先规划”的请求不产生副作用。定时任务到点发送提醒，不在后台自动运行模型或联网查价。", style = XfType.body, color = xf.ink2)
                }
            }
            item {
                PhonePermissionCard("通知", if (notifications) "已允许个人闹钟与提醒通知" else "尚未允许，提醒无法送达", notifications) {
                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS) else settings("notifications")
                }
            }
            item { PhonePermissionCard("闹钟和提醒", if (alarms) "已允许系统准确调度" else "需要在系统设置中手动允许", alarms) { settings("exact_alarms") } }
            item { PhonePermissionCard("无障碍服务", if (accessibility) "服务已开启" else "读取和操作当前屏幕前需要开启", accessibility) { settings("accessibility") } }
            item {
                XfCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("本次手机控制", style = XfType.bodyStrong, color = xf.ink)
                            Text(if (control) "已开启；退出账户或重启应用后自动关闭" else "默认关闭，由你主动开启", style = XfType.small, color = xf.ink2)
                        }
                        Switch(checked = control, onCheckedChange = {
                            PhoneControlSession.setEnabled(scope, it)
                            control = PhoneControlSession.isEnabled(scope)
                        }, enabled = accessibility && PhoneControlSession.isActive(scope))
                    }
                    Text("开启后，星助理可按请求读取当前屏幕并点击、滚动、返回或打开指定应用。可见文字会交给当前选择的模型服务商；密码与输入框会隐藏。不会后台采集。系统授权、登录、付款及发送消息由你手动完成。", style = XfType.small, color = xf.ink2)
                }
            }
            if (message.isNotBlank()) item { Text(message, style = XfType.small, color = xf.accentInk, modifier = Modifier.padding(4.dp)) }
            item { XfSectionHeader("本机记录", subtitle = "当前账户 · ${records.size} 项", action = "刷新", onAction = { refresh() }) }
            if (records.isEmpty()) item { XfCard { Text("还没有本机记录。试着在执行模式说：“明天上午九点提醒我开会”，或“把这份内容保存成笔记文件”。", style = XfType.body, color = xf.ink2) } }
            items(records.take(200), key = { it.optString("id") }) { item ->
                XfCard {
                    Text(item.optString("title", item.optString("name")), style = XfType.bodyStrong, color = xf.ink)
                    val status = when (item.optString("status")) {
                        "saved" -> "已保存"; "scheduled" -> "已安排提醒"; "open" -> "待办"; "needs_time" -> "需要补充通知时刻"; "cancelled" -> "已取消"
                        "delivered" -> "已发出通知"; "delivery_blocked" -> "通知未能送达"; "needs_permission" -> "需要补充授权"
                        "missed" -> "已错过时间"; "firing" -> "触发状态待核实"; else -> "待完成"
                    }
                    Text(status, style = XfType.small, color = xf.ink2)
                    if (item.optString("due_date").isNotBlank()) {
                        Text("目标日期：${item.optString("due_date")} · ${item.optString("timezone")}（未安排通知）", style = XfType.small, color = xf.ink2)
                    }
                    if (item.has("next_at") && item.optString("status") in listOf("scheduled", "needs_permission")) {
                        val time = runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").format(Instant.ofEpochMilli(item.getLong("next_at")).atZone(ZoneId.of(item.getString("timezone")))) }.getOrDefault("")
                        Text("下次：$time${if (item.optString("rule") == "daily") " · 每天" else ""}", style = XfType.small, color = xf.ink2)
                    }
                    if (item.optString("details").isNotBlank()) Text(item.optString("details").take(300), style = XfType.small, color = xf.ink2)
                    if (item.optString("kind") == "file") {
                        Text("${item.optLong("bytes")} 字节 · SHA-256 ${item.optString("sha256").take(12)}…", style = XfType.meta, color = xf.ink3)
                        XfButton("导出文件", onClick = { exporting = scope to item.getString("id"); export.launch(item.getString("name")) }, kind = XfButtonKind.Secondary, enabled = !busy && item.optString("status") == "saved")
                    } else if (item.optString("status") !in listOf("cancelled", "delivered", "delivery_blocked", "missed")) {
                        XfButton("取消事项", onClick = {
                            coroutine.launch {
                                val answer = PhoneToolRuntime(context, scope) { PhoneControlSession.isActive(scope) }.execute("phone_cancel_item", JSONObject().put("id", item.getString("id")), "manual-cancel-${UUID.randomUUID()}")
                                message = runCatching { JSONObject(answer).optString("message") }.getOrDefault("请刷新查看记录。")
                                refresh()
                            }
                        }, kind = XfButtonKind.Ghost, enabled = !busy)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhonePermissionCard(title: String, detail: String, enabled: Boolean, open: () -> Unit) {
    XfCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = XfType.bodyStrong, color = xf.ink)
                Text(detail, style = XfType.small, color = xf.ink2)
            }
            XfButton(if (enabled) "管理" else "去开启", onClick = open, kind = XfButtonKind.Secondary, small = true)
        }
    }
}
