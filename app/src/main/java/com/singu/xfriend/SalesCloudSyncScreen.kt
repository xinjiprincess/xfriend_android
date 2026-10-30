package com.singu.xfriend

import android.app.Application
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class SalesCloudSyncViewModel(app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf(SalesCloudState()); private set
    var loaded by mutableStateOf(false); private set
    var running by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var failure by mutableStateOf(""); private set
    var mediaWarnings by mutableStateOf<List<String>>(emptyList()); private set
    private var blockedAuto = false
    private val mutex = Mutex()
    private var storage: SalesCloudSyncStorage? = null

    private fun storage(friend: FriendViewModel, account: AccountViewModel): SalesCloudSyncStorage {
        val client = account.account ?: error("请先登录账号")
        val identity = client.syncIdentity()
        require(identity.scope == friend.accountScope && account.scope == identity.scope) { "账号或空间已切换，请重新打开同步页面" }
        return storage ?: SalesCloudSyncStorage(getApplication(), identity, friend.store ?: error("资源库尚未加载")).also { storage = it }
    }

    suspend fun load(friend: FriendViewModel, account: AccountViewModel) = mutex.withLock {
        try { state = withContext(Dispatchers.IO) { storage(friend, account).load() }; loaded = true }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { failure = error.message ?: "同步存档无法读取" }
    }

    suspend fun enable(friend: FriendViewModel, account: AccountViewModel, enabled: Boolean) = mutex.withLock {
        try {
            state = withContext(Dispatchers.IO) { storage(friend, account).update { current, _ -> current.copy(enabled = enabled) } }
            blockedAuto = false; failure = ""; loaded = true
            message = if (enabled) "自动同步已开启，在此应用打开时增量同步" else "自动同步已关闭，待发送操作仍保留"
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { failure = error.message ?: "设置保存失败" }
    }

    suspend fun enableMedia(friend: FriendViewModel, account: AccountViewModel, enabled: Boolean) = mutex.withLock {
        try {
            state = withContext(Dispatchers.IO) { storage(friend, account).update { current, _ -> current.copy(mediaEnabled = enabled) } }
            blockedAuto = false; failure = ""
            message = if (enabled) "已开启录音与图片同步，下次同步会传输媒体" else "已关闭媒体传输，已有云端原件和本地下载仍保留"
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { failure = error.message ?: "设置保存失败" }
    }

    suspend fun sync(friend: FriendViewModel, account: AccountViewModel, automatic: Boolean = false) {
        if (!mutex.tryLock()) return
        try {
            if (automatic && (!state.enabled || blockedAuto)) return
            running = true; failure = ""; message = "正在同步当前账号与空间…"
            try {
                val local = withContext(Dispatchers.IO) { storage(friend, account) }
                val client = account.account ?: error("请先登录账号")
                val identity = local.identity
                val transport = object : SalesCloudTransport {
                    private fun checkScope() { require(account.scope == identity.scope && client.scope == identity.scope) { "账号或空间已切换，同步已停止" } }
                    override suspend fun pull(cursor: Long, limit: Int): JSONObject {
                        checkScope()
                        return client.authorizedGet("/api/v2/sync/pull", mapOf("space_id" to identity.space, "cursor" to cursor.toString(), "limit" to limit.toString()), identity.scope).also { checkScope() }
                    }
                    override suspend fun push(operations: List<SalesCloudOperation>): JSONObject {
                        checkScope()
                        return client.authorizedPost("/api/v2/sync/push", salesCloudPushBody(identity, operations), identity.scope).also { checkScope() }
                    }
                }
                // Finish CRM text first so media failures never block reports or customer edits.
                state = withContext(Dispatchers.IO) { SalesCloudSyncEngine(local, transport, identity).sync() }
                if (state.mediaEnabled) {
                    message = "正在传输录音与图片，完成后会关联本地回放…"
                    mediaWarnings = withContext(Dispatchers.IO) {
                        val warnings = mutableListOf<String>()
                        salesCloudMediaAttempt("媒体同步", warnings) {
                            val media = SalesCloudSyncMedia(getApplication(), local, client)
                            salesCloudMediaAttempt("媒体下载", media.warnings) { media.download() }
                            salesCloudMediaAttempt("媒体上传", media.warnings) { media.upload() }
                            warnings.addAll(media.warnings)
                        }
                        warnings.toList()
                    }
                    state = withContext(Dispatchers.IO) { SalesCloudSyncEngine(local, transport, identity).sync() }
                }
                loaded = true; blockedAuto = false
                message = if (state.activeConflicts.isEmpty()) "同步完成" else "同步完成，${state.activeConflicts.size} 项冲突需要你选择；双版本已保留"
                if (mediaWarnings.isNotEmpty()) message += "；${mediaWarnings.size} 项媒体已跳过，CRM 自动同步继续"
                friend.revision++
            } catch (cancel: CancellationException) { message = "同步已暂停，待发送操作已保留"; throw cancel }
            catch (error: Exception) {
                failure = error.message ?: "同步失败，待发送内容已保留"
                blockedAuto = error is SalesCloudHttpException && error.code in setOf("scope_forbidden", "subscription_expired", "session_invalid", "sync_quota_exceeded", "operation_id_reused", "entity_too_large", "invalid_request", "secret_opt_in_required", "media_quota_exceeded", "media_too_large", "media_id_reused", "media_chunk_conflict", "media_checksum_mismatch")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { storage?.let { local -> runCatching { local.load() }.onSuccess { state = it } } }
                running = false
            }
        } finally { mutex.unlock() }
    }

    suspend fun resolve(friend: FriendViewModel, account: AccountViewModel, id: String, keepLocal: Boolean) = mutex.withLock {
        running = true
        try {
            state = withContext(Dispatchers.IO) { storage(friend, account).resolve(id, keepLocal) }
            friend.revision++; failure = ""; blockedAuto = false
            message = if (keepLocal) "已保留本地版本，新的操作等待上传" else "已采用云端版本；原双版本仍保存在冲突历史"
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            failure = error.message ?: "冲突处理失败"
            withContext(Dispatchers.IO) { storage?.let { local -> runCatching { local.load() }.onSuccess { state = it } } }
        } finally { running = false }
    }
}

@Composable private fun salesCloudCoordinator(friend: FriendViewModel): SalesCloudSyncViewModel {
    val app = LocalContext.current.applicationContext as Application
    return viewModel(key = "sales-cloud-sync-${friend.accountScope}", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = SalesCloudSyncViewModel(app) as T
    })
}

/** Mount once outside SalesRoot's navigation branches; no background service or auth tokens are synced. */
@Composable internal fun SalesCloudSyncBinding(friend: FriendViewModel, vm: SalesViewModel, account: AccountViewModel) {
    val coordinator = salesCloudCoordinator(friend)
    LaunchedEffect(friend.accountScope, friend.store, account.scope) {
        if (friend.store == null || account.scope != friend.accountScope) return@LaunchedEffect
        coordinator.load(friend, account)
        while (isActive && account.scope == friend.accountScope) {
            if (coordinator.loaded && coordinator.state.enabled && vm.data != null) coordinator.sync(friend, account, automatic = true)
            delay(if (coordinator.failure.isBlank()) 15_000 else 60_000)
        }
    }
}

/**
 * 云同步页：账号/空间/设备/游标与冲突信息均来自真实同步存档，进度只表达运行中（引擎不提供百分比）。
 * 同步引擎调用、开关写入、冲突处理与密钥授权逻辑保持原样。
 */
@Composable internal fun SalesCloudSyncScreen(friend: FriendViewModel, vm: SalesViewModel, account: AccountViewModel, back: () -> Unit) {
    val colors = xf
    val coordinator = salesCloudCoordinator(friend)
    val scope = rememberCoroutineScope()
    var confirmMedia by remember { mutableStateOf(false) }
    var confirmSecret by remember { mutableStateOf<ModelProfile?>(null) }
    var inspected by remember { mutableStateOf<SalesCloudEntity?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    LaunchedEffect(friend.store, account.scope) { if (friend.store != null) coordinator.load(friend, account) }
    BackHandler(onBack = back)
    val state = coordinator.state
    val blocked = coordinator.running || account.busy
    val session = account.account?.session
    val spaceId = session?.optString("space_id").orEmpty()
    val space = session?.optJSONArray("spaces")?.objects()?.find { it.optString("space_id") == spaceId }
    val accountLabel = listOf(
        session?.optJSONObject("user")?.optString("name").orEmpty(),
        space?.optString("name") ?: spaceId,
    ).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "已登录账号" }
    val deviceLabel = Build.MODEL?.takeIf { it.isNotBlank() } ?: "此手机"
    val profiles = vm.data?.profiles.orEmpty()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(XfDimens.pagePadding, 8.dp, XfDimens.pagePadding, 40.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            Column {
                SalesBackButton("返回", back)
                Spacer(Modifier.height(4.dp))
                XfPageHeader(
                    title = "云同步",
                    subtitle = "此账号与空间单独保存同步进度、待发送操作和冲突。切换空间不会搬移本地资料。",
                )
            }
        }
        item {
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XfIconTile(XfIcons.Globe, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            accountLabel,
                            style = XfType.h3,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text("账号与空间", style = XfType.meta, color = colors.ink3)
                    }
                    XfStatusChip(
                        label = when {
                            coordinator.running -> "同步中"
                            state.enabled -> "自动同步"
                            else -> "手动同步"
                        },
                        tone = when {
                            coordinator.running -> XfTone.Accent
                            state.enabled -> XfTone.Success
                            else -> XfTone.Neutral
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Hr()
                Spacer(Modifier.height(4.dp))
                XfKeyValueRow("设备", deviceLabel)
                XfKeyValueRow("空间", space?.optString("name") ?: spaceId.ifBlank { "未选择空间" })
                XfKeyValueRow("同步游标", state.cursor.toString())
                XfKeyValueRow("待发送", "${state.outbox.size} 项")
                XfKeyValueRow(
                    label = "待处理冲突",
                    value = "${state.activeConflicts.size} 项",
                    valueColor = if (state.activeConflicts.isNotEmpty()) colors.warningText else null,
                )
                if (state.lastSuccess > 0) XfKeyValueRow("上次完成", salesCloudTime(state.lastSuccess))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开启自动同步", style = XfType.bodyStrong, color = colors.ink)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "应用打开时自动同步；离线时保留待发送内容。",
                            style = XfType.meta,
                            color = colors.ink3,
                        )
                    }
                    XfSwitch(
                        checked = state.enabled,
                        onCheckedChange = { value -> scope.launch { coordinator.enable(friend, account, value) } },
                        enabled = coordinator.loaded && !blocked,
                    )
                }
                Spacer(Modifier.height(10.dp))
                XfButton(
                    text = if (coordinator.running) "同步中…" else "立即同步",
                    onClick = { scope.launch { coordinator.sync(friend, account) } },
                    enabled = coordinator.loaded && !blocked,
                    loading = coordinator.running,
                    icon = XfIcons.Refresh,
                    modifier = Modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
                )
                if (coordinator.running) {
                    Spacer(Modifier.height(10.dp))
                    XfProgressBar(progress = null)
                }
                if (coordinator.failure.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    XfBanner(text = coordinator.failure, tone = XfTone.Danger, icon = XfIcons.Bell)
                } else if (coordinator.message.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    XfBanner(
                        text = coordinator.message,
                        tone = when {
                            coordinator.running -> XfTone.Info
                            coordinator.message.contains("完成") || coordinator.message.contains("已开启") -> XfTone.Success
                            coordinator.message.contains("已关闭") || coordinator.message.contains("已暂停") -> XfTone.Warning
                            else -> XfTone.Neutral
                        },
                        icon = when {
                            coordinator.running -> XfIcons.Refresh
                            coordinator.message.contains("完成") -> XfIcons.CheckCircle
                            else -> XfIcons.Bell
                        },
                    )
                }
                if (coordinator.mediaWarnings.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    XfBanner(
                        text = coordinator.mediaWarnings.take(5).joinToString("\n") +
                            if (coordinator.mediaWarnings.size > 5) "\n另有 ${coordinator.mediaWarnings.size - 5} 项媒体提示" else "",
                        tone = XfTone.Warning,
                        icon = XfIcons.Clip,
                    )
                }
            }
        }
        item {
            XfCard {
                Text("同步内容", style = XfType.h3, color = colors.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "客户画像、沟通文字和报告、待办、商机、专家、团队、技能、知识与模型参数。登录凭据不参与同步。",
                    style = XfType.small,
                    color = colors.ink2,
                )
                Spacer(Modifier.height(12.dp))
                Hr()
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("同步录音与图片", style = XfType.bodyStrong, color = colors.ink)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "默认关闭。开启后分块上传原件、下载并校验云端附件，完成后可在沟通详情回放；中断后可继续。",
                            style = XfType.meta,
                            color = colors.ink3,
                        )
                    }
                    XfSwitch(
                        checked = state.mediaEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) confirmMedia = true
                            else scope.launch { coordinator.enableMedia(friend, account, false) }
                        },
                        enabled = coordinator.loaded && !blocked,
                    )
                }
            }
        }
        item {
            XfSectionHeader(
                title = "模型密钥授权",
                subtitle = "每个模型单独选择。关闭后新的同步版本不包含密钥；云端历史和备份可能仍保留已上传的旧版本。",
            )
        }
        item {
            XfCard {
                if (profiles.isEmpty()) {
                    Text("尚未配置模型", style = XfType.bodyStrong, color = colors.ink2)
                    Spacer(Modifier.height(2.dp))
                    Text("在模型配置中添加模型后，可在这里选择是否同步密钥。", style = XfType.meta, color = colors.ink3)
                } else {
                    profiles.forEachIndexed { index, profile ->
                        if (index > 0) Hr()
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            XfIconTile(XfIcons.Cpu, size = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = XfType.bodyStrong, color = colors.ink)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (profile.syncSecret) "包含此模型密钥" else "仅同步参数",
                                    style = XfType.meta,
                                    color = colors.ink3,
                                )
                            }
                            XfSwitch(
                                checked = profile.syncSecret,
                                onCheckedChange = { value ->
                                    if (value) confirmSecret = profile
                                    else vm.update { data ->
                                        data.copy(profiles = data.profiles.map { if (it.id == profile.id) it.copy(syncSecret = false) else it })
                                    }
                                },
                                enabled = !blocked && !vm.busy,
                            )
                        }
                    }
                }
            }
        }
        item {
            XfSectionHeader(
                title = "版本冲突",
                subtitle = if (state.activeConflicts.isNotEmpty()) "${state.activeConflicts.size} 项待处理" else null,
                action = if (showHistory) "仅看待处理" else "查看处理历史",
                onAction = { showHistory = !showHistory },
            )
        }
        val conflicts = if (showHistory) state.conflicts else state.activeConflicts
        if (conflicts.isEmpty()) {
            item {
                XfEmptyState(
                    icon = XfIcons.CheckCircle,
                    title = "暂无待处理冲突",
                    body = "同步完成后，本地与云端不一致的记录会在这里等待你选择保留哪一版。",
                )
            }
        }
        items(conflicts, key = { "conflict-${it.id}" }) { conflict ->
            XfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XfStatusChip(
                        label = if (conflict.choice.isEmpty()) "待处理" else "已处理",
                        tone = if (conflict.choice.isEmpty()) XfTone.Warning else XfTone.Success,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        salesCloudTitle(conflict.local),
                        style = XfType.h3,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("本地", style = XfType.caps, color = colors.ink3)
                Spacer(Modifier.height(2.dp))
                Text(
                    salesCloudSummary(conflict.local),
                    style = XfType.small,
                    color = colors.ink,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                XfLink("查看本地版本", onClick = { inspected = conflict.local })
                Spacer(Modifier.height(8.dp))
                Text("云端 r${conflict.remote.revision}", style = XfType.caps, color = colors.ink3)
                Spacer(Modifier.height(2.dp))
                Text(
                    salesCloudSummary(conflict.remote),
                    style = XfType.small,
                    color = colors.ink,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                XfLink("查看云端版本", onClick = { inspected = conflict.remote })
                if (conflict.choice.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "双版本已加密保留。选择云端会替换此条本地内容；选择本地会提交一个新版本。",
                        style = XfType.meta,
                        color = colors.ink3,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        XfButton(
                            text = "保留本地",
                            onClick = { scope.launch { coordinator.resolve(friend, account, conflict.id, true) } },
                            enabled = !blocked,
                            kind = XfButtonKind.Secondary,
                            small = true,
                            modifier = Modifier.heightIn(min = XfDimens.touch),
                        )
                        XfButton(
                            text = "采用云端",
                            onClick = { scope.launch { coordinator.resolve(friend, account, conflict.id, false) } },
                            enabled = !blocked,
                            small = true,
                            modifier = Modifier.heightIn(min = XfDimens.touch),
                        )
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (conflict.choice == "local") "已选择本地，双版本留档" else "已选择云端，双版本留档",
                        style = XfType.label,
                        color = colors.successText,
                    )
                }
            }
        }
    }
    if (confirmMedia) {
        XfDialog(
            title = "开启录音与图片同步？",
            onDismiss = { confirmMedia = false },
            confirmLabel = "开启",
            onConfirm = {
                confirmMedia = false
                scope.launch { coordinator.enableMedia(friend, account, true) }
            },
            dismissLabel = "取消",
        ) {
            Text(
                "将使用当前网络上传原件并下载当前空间附件。单文件上限100MiB、云端空间媒体配额512MiB。关闭后不会删除已上传原件或本地下载。",
                style = XfType.small,
                color = colors.ink2,
            )
        }
    }
    confirmSecret?.let { profile ->
        XfDialog(
            title = "同步 ${profile.name} 的密钥？",
            onDismiss = { confirmSecret = null },
            confirmLabel = "同意同步此密钥",
            onConfirm = {
                vm.update { data ->
                    data.copy(profiles = data.profiles.map { if (it.id == profile.id) it.copy(syncSecret = true) else it })
                }
                confirmSecret = null
            },
            dismissLabel = "取消",
        ) {
            Text(
                "该模型密钥将上传至此账号的云端加密存储，并可由同账号其他设备取回。关闭授权不能清除此前的历史版本和备份。",
                style = XfType.small,
                color = colors.ink2,
            )
        }
    }
    inspected?.let { entity ->
        XfDialog(
            title = salesCloudTitle(entity),
            onDismiss = { inspected = null },
            confirmLabel = "关闭",
            onConfirm = { inspected = null },
            dismissLabel = null,
        ) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(salesCloudDetail(entity), style = XfType.small, color = colors.ink2)
                }
            }
        }
    }
}

private fun salesCloudTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))

private fun salesCloudTitle(entity: SalesCloudEntity): String {
    val payload = JSONObject(entity.payload)
    return payload.optString("name").ifBlank { payload.optString("title") }.ifBlank { "${entity.type} · ${entity.id}" }
}
private fun salesCloudSummary(entity: SalesCloudEntity): String {
    if (entity.deleted) return "已删除"
    val j = JSONObject(entity.payload)
    return listOf("notes", "text", "report", "prompt", "content", "model", "name", "title").firstNotNullOfOrNull { key -> j.optString(key).takeIf { it.isNotBlank() } } ?: "资料已更新"
}
private fun salesCloudDetail(entity: SalesCloudEntity): String {
    if (entity.deleted) return "此版本为删除记录。"
    // Preview never displays credentials or opaque portable file data.
    val copy = JSONObject(entity.payload)
    copy.remove("_xfpack")
    listOf("key", "apiKey", "api_key", "secrets", "secret", "password", "token", "accessToken", "refreshToken").forEach { if (copy.has(it)) copy.put(it, "（敏感内容不展示）") }
    return copy.toString(2)
}
