package com.singu.xfriend

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.*
import org.json.JSONObject

internal class AccountViewModel(app: Application) : AndroidViewModel(app) {
    // Debug builds can open a local workspace without a cloud account, for device verification of the
    // single-entry UI. Release builds ignore the intent flag because BuildConfig.DEBUG is false.
    val localDebugIntent: Boolean get() = BuildConfig.DEBUG
    var account: CloudAccount? = null
    var ready by mutableStateOf(false)
    var scope by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var epoch = 0L
    private fun bindRuntimeScope(value: String?) {
        PhoneControlSession.activateScope(value.orEmpty())
        value?.let { bound -> FriendStoreRegistry.bindAuthorization(bound) { scope == bound && account?.scope == bound } }
    }
    init { viewModelScope.launch {
        try { account = withContext(Dispatchers.IO) { CloudAccount(app) }; scope = account?.scope; bindRuntimeScope(scope) }
        catch (e: Exception) { error = "账号记录无法读取：${e.message}" }
        ready = true
        while (isActive) {
            account?.takeIf { it.session != null && !busy }?.let { a ->
                val expected = epoch
                try { withContext(Dispatchers.IO) { a.refresh() }; if (epoch == expected) { val old = scope; val next = a.scope; if (old != null && old != next) withContext(Dispatchers.IO) { FriendRemoteVault.clearSession(getApplication(), old); FriendRunService.cancelScope(getApplication(), old).join() }; scope = next; if (old != next) bindRuntimeScope(next) } }
                catch (e: Exception) { if (epoch == expected) { val old = scope; val next = a.scope; if (old != null && old != next) withContext(Dispatchers.IO) { FriendRemoteVault.clearSession(getApplication(), old); FriendRunService.cancelScope(getApplication(), old).join() }; scope = next; if (old != next) bindRuntimeScope(next); if (scope == null) error = e.message ?: "登录状态无法刷新，请联网重试" } }
            }
            delay(60_000)
        }
    } }
    fun submit(register: Boolean, data: JSONObject, remember: Boolean, keep: Boolean) {
        if (busy || account == null) return
        busy = true; error = null; epoch++
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { account!!.authenticate(register, data, remember, keep) }; scope = account!!.scope; bindRuntimeScope(scope) }
            catch (e: Exception) { error = e.message ?: "登录失败，请稍后重试" }
            finally { busy = false }
        }
    }
    fun logout() { if (busy) return; epoch++; val previousScope = scope; scope = null; PhoneControlSession.activateScope(""); busy = true; viewModelScope.launch {
        try { withContext(Dispatchers.IO) { previousScope?.let { FriendRemoteVault.clearSession(getApplication(), it); FriendRunService.cancelScope(getApplication(), it).join() }; account?.logout() } } catch (e: Exception) { error = e.message } finally { busy = false }
    } }
}

/** 品牌标识：概念稿的星形标记 + 产品名，登录页与电脑配对页共用。 */
@Composable internal fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        XfStarMark(size = 44.dp)
        Text(ProductBrand.name, style = XfType.h2, color = xf.ink)
    }
}

@Composable internal fun AccountRoot(onConnectDesktop: () -> Unit, vm: AccountViewModel = viewModel()) {
    val app = LocalContext.current.applicationContext as Application
    val scope = vm.scope
    if (!vm.ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = xf.accent)
                Text("正在读取账号…", style = XfType.meta, color = xf.ink3)
            }
        }
        return
    }
    val localDebug = vm.localDebugIntent && (LocalContext.current as? android.app.Activity)?.intent?.getBooleanExtra("local_workspace", false) == true
    if (localDebug) {
        val localScope = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        // Bind before the view model is created: FriendViewModel captures the authorization
        // generation at construction, and a bind that lands later would leave every store
        // write and task submission failing silently as a stale generation.
        val friend: FriendViewModel = viewModel(key = localScope, factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                PhoneControlSession.activateScope(localScope)
                FriendStoreRegistry.bindAuthorization(localScope) { true }
                return FriendViewModel(app, localScope) as T
            }
        })
        DisposableEffect(friend) { friend.observeRuns(); onDispose { friend.stopObservingRuns() } }
        AssistantRoot(friend, null, onConnectDesktop)
        return
    }
    if (scope != null) {
        val friend: FriendViewModel = viewModel(key = scope, factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = FriendViewModel(app, scope) as T
        })
        DisposableEffect(friend) { friend.observeRuns(); onDispose { friend.stopObservingRuns() } }
        key(scope) {
            if (ProductBrand.isSales) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Box(Modifier.weight(1f)) { SalesRoot(friend, vm, onConnectDesktop) }
                }
            } else {
                AssistantRoot(friend, vm, onConnectDesktop)
            }
        }
        return
    }
    var register by remember { mutableStateOf(false) }
    var identifier by remember(vm.ready) { mutableStateOf(vm.account?.identifier.orEmpty()) }
    var name by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(vm.account?.remember ?: true) }; var keep by remember { mutableStateOf(vm.account?.keep ?: true) }
    var help by remember { mutableStateOf(false) }
    val colors = xf
    Surface(color = colors.canvas) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = XfDimens.pagePadding, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    XfStarMark(size = 56.dp)
                    Text(ProductBrand.name, style = XfType.h1, color = colors.ink)
                    Text(
                        if (ProductBrand.isSales) "让每一次沟通，更有方向。" else "你的灵感与日常，一起完成。",
                        style = XfType.small,
                        color = colors.ink2,
                    )
                }
                XfCard(padding = 20.dp) {
                    Text(
                        if (register) "注册 ${ProductBrand.name}" else "登录 ${ProductBrand.name}",
                        style = XfType.h2,
                        color = colors.ink,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (register) "创建账号后即可使用本机专家与工作区。" else "使用已有账号继续，资料与专家保存在账号空间。",
                        style = XfType.meta,
                        color = colors.ink3,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (register) {
                        AccountField("昵称", name, { name = it.take(100) })
                        Spacer(Modifier.height(12.dp))
                        AccountField("邮箱", email, { email = it.take(254) })
                        Spacer(Modifier.height(12.dp))
                        AccountField("手机号", phone, { phone = it.take(20) })
                    } else AccountField("手机号或邮箱", identifier, { identifier = it.take(254) })
                    Spacer(Modifier.height(12.dp))
                    AccountField("密码", password, { password = it.take(256) }, true)
                    if (register) {
                        Spacer(Modifier.height(12.dp))
                        AccountField("确认密码", confirm, { confirm = it.take(256) }, true)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        AccountToggle(remember, "记住账号") { remember = it }
                        AccountToggle(keep, "保持登录") { keep = it }
                    }
                    vm.error?.let { error ->
                        Spacer(Modifier.height(10.dp))
                        XfBanner(text = error, tone = XfTone.Danger, icon = XfIcons.Bell)
                    }
                    if (help) {
                        Spacer(Modifier.height(10.dp))
                        XfBanner(
                            text = "请联系管理员重置密码。当前未启用短信找回。",
                            tone = XfTone.Info,
                            icon = XfIcons.Help,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    XfButton(
                        text = if (vm.busy) "正在验证…" else if (register) "注册" else "登录",
                        onClick = {
                            if (register && (name.isBlank() || phone.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches())) vm.error = "请填写昵称、手机号和有效邮箱"
                            else if (password.isEmpty() || (!register && identifier.isBlank())) vm.error = "请输入账号和密码"
                            else if (register && password != confirm) vm.error = "两次输入的密码不一致"
                            else {
                                val body = if (register) JSONObject().put("name", name.trim()).put("phone", phone.trim()).put("email", email.trim()) else JSONObject().put("identifier", identifier.trim())
                                body.put("password", password); password = ""; confirm = ""
                                vm.submit(register, body, remember, keep)
                            }
                        },
                        enabled = !vm.busy && vm.account != null,
                        loading = vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        XfLink(if (register) "已有账号？登录" else "没有账号？注册") {
                            if (!vm.busy) {
                                register = !register; password = ""; confirm = ""; vm.error = null; help = false
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (!register) {
                            XfLink("忘记密码？") { help = !help }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Hr()
                    Spacer(Modifier.height(14.dp))
                    XfButton(
                        text = "连接电脑",
                        onClick = onConnectDesktop,
                        enabled = !vm.busy,
                        kind = XfButtonKind.Secondary,
                        icon = XfIcons.Laptop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "连接后使用电脑当前账号和工作空间。",
                        style = XfType.meta,
                        color = colors.ink3,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                Text(
                    "XFAMILY · ${ProductBrand.name} ${BuildConfig.VERSION_NAME}",
                    style = XfType.meta,
                    color = colors.ink4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable private fun RowScope.AccountToggle(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.CheckboxDefaults.colors(
                checkedColor = xf.accentSolid,
                uncheckedColor = xf.ink4,
                checkmarkColor = androidx.compose.ui.graphics.Color.White,
            ),
        )
        Text(label, style = XfType.meta, color = xf.ink2)
    }
}

@Composable private fun AccountField(label: String, value: String, change: (String) -> Unit, secret: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    val trailingSlot: (@Composable () -> Unit)? = if (secret) {
        {
            XfIconButton(
                icon = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (visible) "隐藏密码" else "显示密码",
                onClick = { visible = !visible },
                size = 32.dp,
            )
        }
    } else null
    XfTextField(
        value = value,
        onValueChange = change,
        label = label,
        placeholder = "请输入$label",
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailing = trailingSlot,
    )
}
