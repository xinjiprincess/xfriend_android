package com.singu.xfriend

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

/** Display only known numeric quota fields. Account identities and arbitrary upstream text stay out. */
internal fun friendProviderQuotaNotice(document: JSONObject): String {
    if (document.opt("supported") != true) return "此渠道未提供可查询的账号额度。"
    val values = mutableListOf<String>()
    fun number(label: String, value: Any?, percent: Boolean = false, fraction: Boolean = false) {
        val raw = value as? Number ?: return
        val numeric = raw.toDouble()
        if (!numeric.isFinite() || numeric < 0 || numeric > if (fraction) 1.0 else if (percent) 100.0 else 1_000_000_000_000.0) return
        if (values.size >= 6) return
        val text = BigDecimal.valueOf(if (fraction) numeric * 100 else numeric).stripTrailingZeros().toPlainString()
        values += "$label $text${if (percent || fraction) "%" else ""}"
    }
    val data = document.optJSONObject("data") ?: JSONObject()
    val rate = data.optJSONObject("rate_limit")
    number("主要窗口已用", rate?.optJSONObject("primary_window")?.opt("used_percent"), percent = true)
    number("次要窗口已用", rate?.optJSONObject("secondary_window")?.opt("used_percent"), percent = true)
    number("5 小时已用", data.optJSONObject("five_hour")?.opt("utilization"), percent = true)
    number("7 天已用", data.optJSONObject("seven_day")?.opt("utilization"), percent = true)
    val snapshots = data.optJSONObject("quota_snapshots")
    listOf("chat" to "聊天剩余", "completions" to "补全剩余", "premium_interactions" to "高级请求剩余").forEach { (key, label) ->
        number(label, snapshots?.optJSONObject(key)?.opt("percent_remaining"), percent = true)
    }
    listOf("used" to "已用数", "remaining" to "剩余数", "limit" to "额度上限").forEach { (key, label) -> number(label, data.opt(key)) }
    fun buckets(rows: JSONArray?) {
        if (rows == null) return
        for (index in 0 until rows.length().coerceAtMost(6)) number("配额 ${values.size + 1} 剩余",
            rows.optJSONObject(index)?.opt("remainingFraction"), fraction = true)
    }
    buckets(data.optJSONArray("buckets"))
    data.optJSONArray("accounts")?.let { accounts ->
        for (index in 0 until accounts.length().coerceAtMost(6)) buckets(accounts.optJSONObject(index)?.optJSONArray("buckets"))
    }
    return if (values.isEmpty()) "额度查询完成；服务未提供可安全展示的标准额度字段。"
        else "已查询账号额度：${values.joinToString("；")}。"
}

internal fun friendProviderControllerError(failure: Exception): String = when (failure) {
    is FriendProviderFailure -> failure.userMessage
    is FriendModelFailure -> when (failure.code) {
        "CONFIG_PROVIDER_CHANGED", "MODEL_AUTH_CHANGED" -> "连接配置或授权已更改，请重新打开连接后重试。"
        "CONFIG_URL" -> "请填写不含账号、查询参数或片段的 HTTPS 模型地址。"
        "CONFIG_MODEL" -> "请先选择或填写模型。"
        "CONFIG_PROTOCOL" -> "模型协议不受支持，请检查连接配置。"
        "MODEL_SUBSCRIPTION_QUOTA_ONLY" -> "此渠道不提供聊天推理，请选择 API 渠道或查看账号额度。"
        "MODEL_AUTH_REQUIRED", "HTTP_401", "HTTP_403" -> "模型凭据无效或权限不足，请重新授权或检查密钥。"
        "MODEL_ARK_ENDPOINT_MISMATCH" -> "火山套餐类型与地址不匹配，不会切换到其他计费接口。"
        "DNS", "CONNECT", "TIMEOUT" -> "模型服务暂时无法连接，请检查地址和网络后重试。"
        "TLS" -> "无法安全连接模型服务，请检查证书和设备时间。"
        "HTTP_429" -> "模型服务限流或额度不足，请稍后重试。"
        else -> "模型连接操作未完成，请检查协议、模型和账号授权后重试。"
    }
    is IllegalArgumentException -> "连接配置或账号文档无效，请检查必填信息与文档格式。"
    else -> "模型连接操作未完成，请稍后重试；原有配置已保留。"
}

/** Settings operations share the runtime's scope fence and rotating-token session. No secret logging. */
internal class FriendProviderController(
    private val accountScope: String,
    private val scope: CoroutineScope,
    private val store: () -> Store?,
    private val generation: () -> Long,
    private val idle: () -> Boolean,
    private val changed: () -> Unit,
    private val accounts: FriendProviderAccounts = FriendProviderAccounts(),
    private val transport: FriendModelTransport = FriendModelTransport(),
) {
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var models by mutableStateOf<List<String>>(emptyList())
        private set
    var login by mutableStateOf<ProviderDeviceLogin?>(null)
        private set
    @Volatile private var serial = 0L
    private var job: Job? = null
    private var loginOperation = false
    private val loginFence = Any()
    private var loginEpoch = 0L
    private var committedLoginEpoch = -1L

    private fun guard(operation: Long, authorization: Long) {
        if (operation != serial || !FriendStoreRegistry.authorized(accountScope, authorization))
            throw CancellationException("authorization_ended")
    }

    private fun operation(message: String, isLogin: Boolean = false,
        action: suspend (Store, Long, Long) -> Unit) {
        if (busy || !idle()) { notice = "正在处理上一个操作，请稍后再试。"; return }
        val state = store() ?: run { error = "工作区尚未就绪，请稍后重试。"; return }
        val authorization = generation()
        if (!FriendStoreRegistry.authorized(accountScope, authorization)) { error = "账号授权已结束，请重新打开工作区。"; return }
        val operation = ++serial
        busy = true; error = null; notice = message; loginOperation = isLogin
        job = scope.launch {
            val current = currentCoroutineContext()[Job]!!
            val watcher = scope.launch {
                while (isActive) {
                    delay(200)
                    if (!FriendStoreRegistry.authorized(accountScope, authorization)) { current.cancel(); break }
                }
            }
            try {
                guard(operation, authorization)
                action(state, authorization, operation)
                currentCoroutineContext().ensureActive(); guard(operation, authorization)
                changed()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (operation == serial && FriendStoreRegistry.authorized(accountScope, authorization)) {
                    error = friendProviderControllerError(failure); notice = ""
                }
            } finally {
                watcher.cancel()
                if (operation == serial) { busy = false; job = null; loginOperation = false }
            }
        }
    }

    private suspend fun mutate(state: Store, authorization: Long, operation: Long, loginTicket: Long? = null,
        action: (Store) -> Unit) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive(); guard(operation, authorization)
        FriendStoreRegistry.withAuthorization(accountScope, authorization) {
            synchronized(state) {
                fun commit() {
                    guard(operation, authorization)
                    val previous = state.modelConnections; val active = state.activeModelConnectionId
                    try { action(state); state.save() }
                    catch (failure: Exception) { state.modelConnections = previous; state.activeModelConnectionId = active; throw failure }
                }
                if (loginTicket == null) commit() else synchronized(loginFence) {
                    if (loginEpoch != loginTicket || login == null) throw CancellationException("login_cancelled")
                    commit(); committedLoginEpoch = loginTicket
                }
            }
        }
    }

    private fun sanitized(connection: FriendModelConnection): FriendModelConnection = connection.copy(
        name = connection.name.trim(), model = connection.model.trim(),
        baseUrl = if (connection.baseUrl.isBlank()) "" else normalizeFriendProviderBaseUrl(connection.baseUrl, connection.apiFormat),
        models = connection.models.filter { it.isNotBlank() }.distinct(), credentials = connection.credentials.toMap(),
        metadata = FriendModelConnectionCodec.sanitizedMetadata(connection.metadata),
    ).also { FriendModelConnectionCodec.validate(it) }

    private fun nativeFieldsMatch(saved: FriendModelConnection, draft: FriendModelConnection): Boolean =
        saved.baseUrl == draft.baseUrl && (saved.apiFormat == draft.apiFormat ||
            saved.providerType == "github_copilot" && saved.apiFormat in setOf("openai_chat", "openai_responses") &&
            draft.apiFormat in setOf("openai_chat", "openai_responses"))

    private fun updated(saved: FriendModelConnection?, draft: FriendModelConnection): FriendModelConnection {
        if (saved != null) {
            if (saved.generation != draft.generation || saved.providerType != draft.providerType || saved.providerId != draft.providerId)
                throw FriendModelFailure("CONFIG_PROVIDER_CHANGED", "连接配置已更改")
            if (saved.providerType !in setOf("api", "volcengine_ark") && !nativeFieldsMatch(saved, draft))
                throw FriendModelFailure("CONFIG_PROVIDER_CHANGED", "账号渠道不可改写")
        }
        val credentials = if (saved == null) draft.credentials else if (draft.providerType in setOf("api", "volcengine_ark"))
            friendProviderEditedCredentials(saved.credentials, draft.credentials["api_key"].orEmpty()) else saved.credentials
        return sanitized(draft.copy(credentials = credentials, generation = Math.addExact(saved?.generation ?: 0L, 1L)))
    }

    fun save(connection: FriendModelConnection) = operation("正在保存连接…") { state, authorization, operation ->
        val draft = sanitized(connection)
        mutate(state, authorization, operation) { s ->
            val saved = s.modelConnections.firstOrNull { it.id == draft.id }
            val next = updated(saved, draft)
            s.modelConnections = s.modelConnections.filterNot { it.id == next.id } + next
        }
        notice = "连接已保存到本机安全存储。"
    }

    /** A saved snapshot is resolved first, but an edited API key remains a draft until Save. */
    private suspend fun resolve(state: Store, authorization: Long, operation: Long, draft: FriendModelConnection): FriendModelConnection {
        val clean = sanitized(draft)
        val saved = FriendStoreRegistry.withAuthorization(accountScope, authorization) {
            synchronized(state) { state.modelConnections.firstOrNull { it.id == clean.id } }
        }
        val result = if (saved != null) {
            if (saved.generation != clean.generation || saved.providerType != clean.providerType || saved.providerId != clean.providerId)
                throw FriendModelFailure("CONFIG_PROVIDER_CHANGED", "连接配置已更改")
            val refreshed = FriendProviderSession(accountScope, authorization, state, accounts).resolve(saved)
            if (clean.providerType in setOf("api", "volcengine_ark")) clean.copy(credentials = friendProviderEditedCredentials(refreshed.credentials, clean.credentials["api_key"].orEmpty()))
            else {
                if (!nativeFieldsMatch(saved, clean)) throw FriendModelFailure("CONFIG_PROVIDER_CHANGED", "账号渠道不可改写")
                clean.copy(credentials = refreshed.credentials)
            }
        } else {
            if (clean.providerType !in setOf("api", "volcengine_ark")) throw FriendModelFailure("MODEL_AUTH_REQUIRED", "请先保存账号授权")
            accounts.refresh(clean)
        }
        currentCoroutineContext().ensureActive(); guard(operation, authorization)
        return result
    }

    private fun preflight(connection: FriendModelConnection) {
        if (!FriendProviderCatalog.canInfer(connection)) throw FriendModelFailure("MODEL_SUBSCRIPTION_QUOTA_ONLY", "此渠道不提供推理")
        FriendProviderWire.prepare(connection, JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "OK")))
            .put("max_tokens", 1), UUID.randomUUID().toString())
    }

    fun select(id: String) = operation("正在检查当前连接…") { state, authorization, operation ->
        val selected = FriendStoreRegistry.withAuthorization(accountScope, authorization) {
            synchronized(state) { state.modelConnections.firstOrNull { it.id == id } }
        } ?: throw FriendModelFailure("CONFIG_PROVIDER_CHANGED", "连接不存在")
        preflight(resolve(state, authorization, operation, selected))
        mutate(state, authorization, operation) { s ->
            if (s.modelConnections.none { it.id == id && it.enabled && it.generation == selected.generation })
                throw FriendModelFailure("CONFIG_PROVIDER_CHANGED", "连接配置已更改")
            s.activeModelConnectionId = id
        }
        notice = "已切换当前模型连接，新请求将使用此连接。"
    }

    fun delete(id: String) = operation("正在删除连接…") { state, authorization, operation ->
        mutate(state, authorization, operation) { s -> s.modelConnections = s.modelConnections.filterNot { it.id == id } }
        // Keep an active deleted id: inference must fail closed instead of using legacy credentials.
        notice = "连接已删除；如需继续聊天，请选择其他连接。"
    }

    fun test(connection: FriendModelConnection) = operation("正在测试模型连接…") { state, authorization, operation ->
        val ready = resolve(state, authorization, operation, connection)
        val tools = synchronized(state) { state.modelUseTools }
        val result = transport.testConnection(ready, tools)
        guard(operation, authorization); notice = result
    }

    fun fetchModels(connection: FriendModelConnection) {
        if (busy || !idle()) { notice = "正在处理上一个操作，请稍后再试。"; return }
        models = emptyList()
        operation("正在获取模型列表…") { state, authorization, operation ->
            val ready = resolve(state, authorization, operation, connection)
            val result = accounts.fetchModels(ready).filter { it.isNotBlank() && it.length <= 256 && it.none { c -> c < ' ' || c == '\u007f' } }.distinct().take(4096)
            guard(operation, authorization); models = result; notice = "已获取 ${result.size} 个模型；请选择模型后保存连接。"
        }
    }

    fun quota(connection: FriendModelConnection) = operation("正在查询账号额度…") { state, authorization, operation ->
        val ready = resolve(state, authorization, operation, connection)
        val result = accounts.fetchQuota(ready)
        guard(operation, authorization); notice = friendProviderQuotaNotice(result)
    }

    fun startLogin(type: String) {
        if (busy || !idle()) { notice = "正在处理上一个操作，请稍后再试。"; return }
        cancelLogin(false)
        val ticket = synchronized(loginFence) { ++loginEpoch }
        operation("正在请求设备授权码…", isLogin = true) { _, authorization, operation ->
            val started = accounts.startLogin(type)
            try {
                currentCoroutineContext().ensureActive(); guard(operation, authorization)
                synchronized(loginFence) {
                    if (loginEpoch != ticket) throw CancellationException("login_cancelled")
                    login = started
                }
                notice = "请在授权页面输入设备码，再查看授权状态。"
            } catch (failure: Exception) { started.cancel(); throw failure }
        }
    }

    fun pollLogin() {
        val pending = login ?: run { error = "没有待完成的设备授权，请重新开始。"; return }
        val ticket = synchronized(loginFence) { loginEpoch }
        operation("正在查看授权状态…", isLogin = true) { state, authorization, operation ->
            val result = accounts.pollLogin(pending)
            currentCoroutineContext().ensureActive(); guard(operation, authorization)
            synchronized(loginFence) { if (loginEpoch != ticket || login !== pending) throw CancellationException("login_cancelled") }
            when (result) {
                is ProviderLoginResult.Pending -> notice = if (result.slowDown) "授权尚未完成，请等待后再查看状态。" else "等待你在授权页面完成登录。"
                is ProviderLoginResult.Failed -> { pending.cancel(); login = null; error = "设备授权未完成、已取消或已过期，请重新开始。"; notice = "" }
                is ProviderLoginResult.Complete -> {
                    val connection = sanitized(result.connection)
                    mutate(state, authorization, operation, ticket) { s ->
                        require(s.modelConnections.none { it.id == connection.id })
                        s.modelConnections = s.modelConnections + connection
                    }
                    synchronized(loginFence) { if (loginEpoch == ticket) login = null }
                    notice = "账号授权已保存。请编辑连接，获取并选择模型后设为当前。"
                }
            }
        }
    }

    fun cancelLogin(showNotice: Boolean = true) {
        var alreadyCommitted = false
        val shouldCancel = synchronized(loginFence) {
            val committed = committedLoginEpoch == loginEpoch
            alreadyCommitted = committed
            ++loginEpoch; login?.cancel(); login = null
            loginOperation && !committed
        }
        if (shouldCancel) {
            ++serial; job?.cancel(); job = null; busy = false; loginOperation = false
        }
        if (showNotice) { notice = if (alreadyCommitted) "授权已完成，连接已保存；尚未切换当前模型。" else "设备授权已取消。"; error = null }
    }

    fun importDocument(type: String, text: String) = operation("正在导入所选账号文档…") { state, authorization, operation ->
        val connection = withContext(Dispatchers.IO) { sanitized(accounts.importDocument(type, text)) }
        mutate(state, authorization, operation) { s ->
            require(s.modelConnections.none { it.id == connection.id }); s.modelConnections = s.modelConnections + connection
        }
        notice = "账号文档已导入安全存储。请选择模型；此操作不会自动切换当前连接。"
    }

    fun close() { cancelLogin(false); ++serial; job?.cancel(); job = null; busy = false }
}
