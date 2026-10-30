package com.singu.xfriend

import android.annotation.SuppressLint
import android.webkit.*
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal fun companionOrigin(value: String): String {
    val uri = URI(value.trim())
    require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "请输入电脑连接桥的完整地址，不带路径或账号" }
    require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in listOf("127.0.0.1", "localhost"))) { "网络连接必须使用 HTTPS；USB 连接可用本机回环地址" }
    return "${uri.scheme}://${uri.rawAuthority}"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun CompanionScreen(onUsePhone: () -> Unit) {
    val context = LocalContext.current
    val colors = xf
    val prefs = remember { context.getSharedPreferences("desktop_connection", 0) }
    var address by remember { mutableStateOf(prefs.getString("address", "http://127.0.0.1:8765").orEmpty()) }
    var pairing by remember { mutableStateOf("") }
    var origin by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(100) }
    var disconnecting by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files -> fileCallback?.onReceiveValue(files.takeIf { it.isNotEmpty() }?.toTypedArray()); fileCallback = null }
    DisposableEffect(Unit) { onDispose { fileCallback?.onReceiveValue(null) } }
    fun disconnect(usePhone: Boolean) {
        if (disconnecting) return
        val target = origin
        val cookie = target?.let { CookieManager.getInstance().getCookie(it) }.orEmpty()
        disconnecting = true
        browser?.stopLoading()
        browser?.loadUrl("about:blank")
        uiScope.launch {
            withContext(Dispatchers.IO) {
                if (target != null) runCatching {
                    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
                        .newCall(Request.Builder().url("$target/mobile/logout").header("Origin", target).header("Cookie", cookie).post(byteArrayOf().toRequestBody()).build()).execute().close()
                }
            }
            browser?.clearHistory(); browser?.clearCache(true)
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData()
                origin = null; pairing = ""; error = null; disconnecting = false
                if (usePhone) onUsePhone()
            }
        }
    }
    BackHandler {
        if (origin == null) { disconnect(true) }
        else if (!disconnecting && browser?.canGoBack() == true) browser?.goBack()
        else disconnect(true)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        if (origin == null) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(XfDimens.pagePadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BrandHeader()
                XfPageHeader(
                    title = "连接电脑版",
                    subtitle = "在电脑版的手机连接页面或连接桥中查看地址和配对码。连接后使用电脑上的专家、团队、技能包、知识库和已启用的电脑控制能力。",
                )
                XfCard {
                    XfTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = "电脑连接地址",
                        placeholder = "http://127.0.0.1:8765",
                    )
                    Spacer(Modifier.height(12.dp))
                    XfTextField(
                        value = pairing,
                        onValueChange = { pairing = it },
                        label = "电脑显示的配对码",
                        placeholder = "输入完整配对码",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            autoCorrect = false,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                    )
                    error?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        XfBanner(text = message, tone = XfTone.Danger, icon = XfIcons.Bell)
                    }
                    Spacer(Modifier.height(16.dp))
                    XfButton(
                        text = if (connecting) "正在检查电脑版…" else "连接并同步",
                        onClick = {
                            val target = runCatching {
                                require(pairing.length >= 16) { "请输入电脑显示的完整配对码" }
                                companionOrigin(address)
                            }.getOrElse { error = it.message; return@XfButton }
                            connecting = true; error = null
                            uiScope.launch {
                                try {
                                    withContext(Dispatchers.IO) { probeDesktop(target) }
                                    prefs.edit().putString("address", target).apply(); origin = target
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { error = e.message ?: "电脑连接失败，请检查地址和网络" }
                                finally { connecting = false }
                            }
                        },
                        enabled = !connecting && !disconnecting,
                        loading = connecting,
                        icon = XfIcons.Laptop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    XfButton(
                        text = "返回此手机",
                        onClick = { disconnect(true) },
                        enabled = !disconnecting && !connecting,
                        kind = XfButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                XfBanner(
                    text = "数据由电脑保存，电脑需保持运行。USB：电脑执行 adb reverse tcp:8765 tcp:8765。局域网或远程连接需受信任的 HTTPS 证书。",
                    tone = XfTone.Neutral,
                    icon = XfIcons.Help,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "电脑版 · 实时连接",
                    style = XfType.title,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                XfButton(
                    text = "重连",
                    onClick = { error = null; browser?.reload() },
                    enabled = !disconnecting,
                    kind = XfButtonKind.Ghost,
                    small = true,
                )
                XfButton(
                    text = if (disconnecting) "断开中…" else "断开",
                    onClick = { disconnect(false) },
                    enabled = !disconnecting,
                    kind = XfButtonKind.Ghost,
                    small = true,
                )
                XfButton(
                    text = "此手机",
                    onClick = { disconnect(true) },
                    enabled = !disconnecting,
                    kind = XfButtonKind.Secondary,
                    small = true,
                )
            }
            if (progress < 100) XfProgressBar(progress = progress / 100f, modifier = Modifier.padding(horizontal = 12.dp))
            error?.let { message ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    XfBanner(text = message, tone = XfTone.Danger, icon = XfIcons.Bell)
                }
            }
            val target = origin!!
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
                WebView(ctx).apply {
                    browser = this
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                            fileCallback?.onReceiveValue(null); fileCallback = callback
                            picker.launch(params.acceptTypes.filter { it.contains('/') }.toTypedArray().ifEmpty { arrayOf("*/*") })
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            // AndroidView.onRelease disposes the failed WebView after this state change.
                            browser = null; origin = null
                            error = "页面进程已被系统回收，请重新配对连接"
                            return true
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            val allowed = runCatching { companionOrigin("${u.scheme}://${u.encodedAuthority}") == target }.getOrDefault(false)
                            if (!allowed) error = "已阻止离开配对的电脑地址"
                            return !allowed
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) { if (request.isForMainFrame) error = "电脑连接中断，请确认连接桥和电脑版正在运行" }
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: android.net.http.SslError) { handler.cancel(); error = "电脑证书不受信任，请配置受信任的 HTTPS 或使用 USB 连接" }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) { if (request.isForMainFrame && response.statusCode >= 400) error = "连接被拒绝（${response.statusCode}），请断开并重新配对" }
                    }
                    postUrl("$target/mobile/pair", ("code=" + java.net.URLEncoder.encode(pairing, "UTF-8")).toByteArray())
                    pairing = ""
                }
            }, onRelease = { it.destroy(); if (browser === it) browser = null })
        }
    }
}
