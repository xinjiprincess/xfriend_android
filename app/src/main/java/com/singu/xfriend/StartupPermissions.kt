package com.singu.xfriend

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat

private data class StartupPermissionState(
    val microphone: Boolean,
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val accessibility: Boolean,
) {
    val runtimeMissing: List<String> get() = buildList {
        if (!microphone) add(Manifest.permission.RECORD_AUDIO)
        if (!notifications && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val coreReady: Boolean get() = microphone && notifications && exactAlarms
}

private fun readStartupPermissions(activity: Activity): StartupPermissionState = StartupPermissionState(
    microphone = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    notifications = PhonePermissions.notifications(activity),
    exactAlarms = PhonePermissions.exactAlarms(activity),
    accessibility = PhonePermissions.accessibility(activity),
)

private fun openPermissionSettings(activity: Activity, kind: String) {
    runCatching { activity.startActivity(PhonePermissions.settingsIntent(activity, kind)) }
}

/**
 * Requests the permissions the phone assistant actually needs when the app starts. Runtime
 * permissions use the system dialog; exact alarms and accessibility are special accesses and are
 * only opened after an explicit tap. Denial never crashes or fabricates a grant.
 */
@Composable
internal fun StartupPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    if (activity == null) { content(); return }
    var state by remember { mutableStateOf(readStartupPermissions(activity)) }
    var show by rememberSaveable { mutableStateOf(false) }
    var askedRuntime by rememberSaveable { mutableStateOf(false) }

    fun refresh(showDialog: Boolean = true) {
        state = readStartupPermissions(activity)
        if (showDialog) show = !state.coreReady
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh(showDialog = true)
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh(showDialog = show) }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(activity, askedRuntime) {
        if (askedRuntime) return@LaunchedEffect
        askedRuntime = true
        state = readStartupPermissions(activity)
        if (state.runtimeMissing.isEmpty()) {
            show = !state.coreReady
        } else {
            // System runtime dialog appears immediately; the explanatory dialog follows only if
            // something is still missing after the user answers.
            launcher.launch(state.runtimeMissing.toTypedArray())
        }
    }
    content()
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("开启星助理所需权限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("语音输入需要麦克风；任务提醒需要通知和准确闹钟；手机界面控制需要无障碍服务（可选，可稍后手动开启）。", fontSize = 14.sp)
                Spacer(Modifier.height(2.dp))
                PermissionLine("麦克风", state.microphone)
                PermissionLine("通知", state.notifications)
                PermissionLine("准确闹钟", state.exactAlarms)
                PermissionLine("无障碍（可选）", state.accessibility)
                if (state.runtimeMissing.isNotEmpty()) {
                    TextButton(onClick = { launcher.launch(state.runtimeMissing.toTypedArray()) }) { Text("开启麦克风/通知") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openPermissionSettings(activity, "notifications") }) { Text("通知设置") }
                    TextButton(onClick = { openPermissionSettings(activity, "exact_alarms") }) { Text("闹钟设置") }
                    TextButton(onClick = { openPermissionSettings(activity, "accessibility") }) { Text("无障碍设置") }
                }
                Text("未授权时，相应功能会说明实际缺失项；不会伪造授权或把设置页打开当成已授权。", fontSize = 12.sp, color = xf.ink3)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (state.runtimeMissing.isNotEmpty()) launcher.launch(state.runtimeMissing.toTypedArray())
                else show = false
            }) { Text(if (state.runtimeMissing.isNotEmpty()) "继续授权" else "知道了") }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text("稍后") } },
    )
}

@Composable
private fun PermissionLine(label: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (granted) "✓" else "○", color = if (granted) xf.successText else xf.ink3)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, color = xf.ink, modifier = Modifier.weight(1f))
        Text(if (granted) "已允许" else "待开启", fontSize = 12.sp, color = if (granted) xf.successText else xf.warningText)
    }
}
