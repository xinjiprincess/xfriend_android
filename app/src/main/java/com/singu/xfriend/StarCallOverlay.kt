package com.singu.xfriend

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class CallPhase { Ready, Recording, Transcribing, Thinking, Speaking, Failed }

@Composable
internal fun StarCallOverlay(vm: FriendViewModel, store: Store, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { StarVoiceRecorder(context.applicationContext) }
    val speech = remember { StarSpeech(context.applicationContext) }
    var phase by remember { mutableStateOf(CallPhase.Ready) }
    var elapsed by remember { mutableStateOf(0L) }
    var recordingStartedAt by remember { mutableStateOf(0L) }
    var transcript by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(true) }

    val asrReady = store.asrUrl.isNotBlank()
    val microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) phase = CallPhase.Ready else notice = "没有麦克风权限；请允许录音后再试。"
    }

    fun startListening() {
        if (!active) return
        if (!asrReady) { notice = "还没有配置语音识别（ASR）；可先到设置里填写 ASR 地址。"; phase = CallPhase.Failed; return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        recorder.start().onSuccess { notice = ""; transcript = ""; reply = ""; phase = CallPhase.Recording; elapsed = 0; recordingStartedAt = System.currentTimeMillis() }
            .onFailure { notice = "无法开始录音：${it.message ?: "设备忙"}"; phase = CallPhase.Failed }
    }

    fun stopAndSend() {
        if (phase != CallPhase.Recording) return
        val file = recorder.stop()
        if (file == null) { notice = "录音太短，请再说一次。"; phase = CallPhase.Ready; return }
        phase = CallPhase.Transcribing
        scope.launch {
            when (val result = starTranscribe(context.applicationContext, file, store)) {
                is StarTranscribe.Ok -> {
                    transcript = result.text
                    // Calls always target the main assistant, never a leftover subagent target.
                    vm.targetAgentRunId = null
                    vm.replyTarget = null
                    val id = vm.sendFromCall(result.text)
                    if (id == null) {
                        notice = vm.error ?: "消息未提交；请检查模型配置。"
                        phase = CallPhase.Failed
                    } else {
                        requestId = id
                        reply = ""
                        phase = CallPhase.Thinking
                    }
                }
                is StarTranscribe.Failed -> { notice = result.message; phase = CallPhase.Failed }
            }
        }
    }

    val run = requestId?.let { id -> vm.runList.firstOrNull { it.requestMessageId == id || it.clientRequestId == id } }
    val inboxEntry = requestId?.let { id -> vm.inbox.firstOrNull { it.requestId == id } }
    LaunchedEffect(Unit) { if (microphone && asrReady) startListening() else if (!asrReady) notice = "还没有配置语音识别（ASR）；文字对话仍可用。" }
    LaunchedEffect(phase) {
        if (phase != CallPhase.Recording) return@LaunchedEffect
        while (phase == CallPhase.Recording && active) {
            elapsed = (System.currentTimeMillis() - recordingStartedAt) / 1000
            delay(300)
        }
    }
    LaunchedEffect(inboxEntry?.state, inboxEntry?.message) {
        if (inboxEntry?.state in setOf("提交失败", "已中断") && phase == CallPhase.Thinking) {
            notice = inboxEntry?.message.orEmpty().ifBlank { "消息提交失败。" }
            phase = CallPhase.Failed
        }
    }
    LaunchedEffect(requestId, run?.state) {
        val current = run ?: return@LaunchedEffect
        when (current.state) {
            "completed" -> {
                reply = current.partial.ifBlank { current.errorMessage }.ifBlank { "任务已完成。" }
                phase = CallPhase.Speaking
                speech.speak(reply) {
                    if (active) { phase = CallPhase.Ready; startListening() }
                }
            }
            "failed", "interrupted", "cancelled", "requires_review" -> {
                notice = current.errorMessage.ifBlank { "任务未完成。" }
                phase = CallPhase.Failed
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            active = false
            recorder.cancel()
            speech.shutdown()
        }
    }

    Dialog(onDismissRequest = { recorder.cancel(); speech.stop(); onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = xf.surface) {
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    XfStarMark(size = 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("正在和星助手对话", style = XfType.h2, color = xf.ink)
                        Text(when (phase) {
                            CallPhase.Ready -> "可以开始说话"
                            CallPhase.Recording -> "正在聆听…"
                            CallPhase.Transcribing -> "正在识别语音…"
                            CallPhase.Thinking -> "正在处理消息…"
                            CallPhase.Speaking -> "正在朗读回复…"
                            CallPhase.Failed -> "通话暂停"
                        }, style = XfType.meta, color = xf.ink3)
                    }
                    XfButton("结束", {
                        recorder.cancel(); speech.stop(); onClose()
                    }, kind = XfButtonKind.Ghost, small = true)
                }
                Spacer(Modifier.height(28.dp))
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(
                            Modifier.size(120.dp).clip(CircleShape).background(if (phase == CallPhase.Recording) xf.accentSoft else xf.surface2),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (phase) {
                                CallPhase.Transcribing, CallPhase.Thinking, CallPhase.Speaking ->
                                    CircularProgressIndicator(Modifier.size(38.dp), strokeWidth = 3.dp, color = xf.accent)
                                else -> Icon(XfIcons.Mic, "麦克风", Modifier.size(42.dp), tint = xf.accentInk)
                            }
                        }
                        Text(when (phase) {
                            CallPhase.Ready -> "点按麦克风开始说话"
                            CallPhase.Recording -> "正在聆听 ${elapsed}s，点按结束并发送"
                            CallPhase.Transcribing -> "正在把语音转成文字"
                            CallPhase.Thinking -> if (vm.runList.any { it.active && System.currentTimeMillis() - it.created >= 10_000 })
                                "正在处理消息…" else "正在等待星助手回复"
                            CallPhase.Speaking -> "星助手正在回答"
                            CallPhase.Failed -> "可以点按重试"
                        }, style = XfType.body, color = xf.ink2)
                        if (notice.isNotBlank()) Text(notice, style = XfType.small, color = xf.warningText,
                            modifier = Modifier.padding(horizontal = 18.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        XfButton(
                            text = when (phase) {
                                CallPhase.Recording -> "结束并发送"
                                CallPhase.Transcribing, CallPhase.Thinking, CallPhase.Speaking -> "请稍候…"
                                else -> "开始说话"
                            },
                            onClick = { if (phase == CallPhase.Recording) stopAndSend() else startListening() },
                            enabled = phase !in setOf(CallPhase.Transcribing, CallPhase.Thinking, CallPhase.Speaking),
                            kind = XfButtonKind.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (transcript.isNotBlank()) {
                            XfCard(Modifier.fillMaxWidth()) {
                                Text("你", style = XfType.label, color = xf.ink3)
                                Text(transcript, style = XfType.body, color = xf.ink)
                            }
                        }
                        if (reply.isNotBlank()) {
                            XfCard(Modifier.fillMaxWidth()) {
                                Text("星助手", style = XfType.label, color = xf.ink3)
                                Text(reply, style = XfType.body, color = xf.ink, maxLines = 8, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text("通话使用本机麦克风与已配置的语音识别/模型服务；录音转写完成后即删除。", style = XfType.meta,
                    color = xf.ink4, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
