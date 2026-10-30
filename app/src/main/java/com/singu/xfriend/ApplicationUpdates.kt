package com.singu.xfriend

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

internal data class ApplicationRelease(val version: String, val sequence: Long, val url: String, val size: Long, val sha256: String)

internal fun verifyApplicationRelease(envelope: JSONObject, product: String, sequence: Long, now: Instant = Instant.now()): ApplicationRelease {
    require(envelope.getString("key_id") == "bd7abf7f55057add") { "更新发布者不受信任" }
    val payload = Base64.getDecoder().decode(envelope.getString("payload"))
    val signature = Base64.getDecoder().decode(envelope.getString("signature"))
    val key = Base64.getDecoder().decode("LzSKYGWOuf5hb5MexrxRF6ld3HAFUlkd4UWCkYnwurE=")
    val verifier = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(key, 0)); update(payload, 0, payload.size) }
    require(verifier.verifySignature(signature)) { "更新签名校验失败" }
    val j = JSONObject(String(payload, Charsets.UTF_8))
    require(j.getInt("format") == 1 && j.getString("application") == product && j.getString("flavor") == product && j.getString("platform") == "android" && j.getString("channel") == "stable") { "更新与当前应用不兼容" }
    require(j.getInt("minimum_updater") == 1 && j.getLong("sequence") >= sequence && j.getLong("sequence") > 0 && Instant.parse(j.getString("expires")).isAfter(now)) { "更新已过期或发布序号回退" }
    val file = j.getJSONObject("installer")
    val url = java.net.URI(file.getString("url"))
    require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null && url.fragment == null) { "更新下载地址不受支持" }
    val sha = file.getString("sha256")
    require(sha.matches(Regex("[a-f0-9]{64}")) && file.getLong("size") in 1..(512L * 1024 * 1024)) { "更新包大小或摘要不正确" }
    return ApplicationRelease(j.getString("version"), j.getLong("sequence"), url.toString(), file.getLong("size"), sha)
}

internal class ApplicationUpdater(private val context: Context) {
    private val product = if (context.packageName.contains("xsalers")) "xsalers" else "xfriend"
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("xf_application_updates", Context.MODE_PRIVATE)
    suspend fun check(): ApplicationRelease = withContext(Dispatchers.IO) {
        val address = "https://www.ciyuancenter.cc/xfriendmanager/api/v2/releases/current?product=$product&platform=android&flavor=$product&channel=stable"
        http.newCall(Request.Builder().url(address).build()).execute().use { response ->
            check(response.isSuccessful) { if (response.code == 404) "当前渠道尚无已发布更新" else "更新服务暂不可用（${response.code}）" }
            val source = response.body?.source() ?: error("更新服务返回空内容")
            require(!source.request(65537)) { "更新清单过大" }
            verifyApplicationRelease(JSONObject(source.readUtf8()), product, prefs.getLong("sequence", 0))
        }
    }
    suspend fun download(release: ApplicationRelease, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "application-updates").apply { mkdirs() }
        val target = File(directory, "update.apk")
        val temporary = File(directory, "update.download")
        try {
            http.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                check(response.isSuccessful) { "更新下载失败（${response.code}）" }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                response.body!!.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        count += n; require(count <= release.size) { "更新包超过声明大小" }
                        digest.update(buffer, 0, n); output.write(buffer, 0, n)
                        progress((count * 100 / release.size).toInt())
                    }
                } }
                require(count == release.size && digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "更新包校验失败，请重新下载" }
            }
            verifyApk(temporary)
            if (target.exists()) check(target.delete())
            check(temporary.renameTo(target)) { "无法保存已校验安装包" }
            prefs.edit().putLong("sequence", release.sequence).apply()
            target
        } finally { temporary.delete() }
    }
    @Suppress("DEPRECATION") private fun verifyApk(file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: error("更新 APK 无法读取")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "安装包属于其他应用" }
        val newVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val oldVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(newVersion > oldVersion) { "已经是当前版本，或安装包版本过旧" }
        val newSignatures = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val oldSignatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        require(!newSignatures.isNullOrEmpty() && !oldSignatures.isNullOrEmpty() && newSignatures.map { it.toCharsString() }.toSet() == oldSignatures.map { it.toCharsString() }.toSet()) { "安装签名与当前版本不一致，不能覆盖更新" }
    }
    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

@Composable internal fun ApplicationUpdateCard() {
    val context = LocalContext.current
    val updater = remember { ApplicationUpdater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<ApplicationRelease?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("当前版本 ${BuildConfig.VERSION_NAME} · 安装前由系统再次确认") }
    XfCard {
        Text("关于与在线更新", style = XfType.title, color = xf.ink)
        Spacer(Modifier.height(8.dp)); Text(message, style = XfType.meta, color = xf.ink3); Spacer(Modifier.height(12.dp))
        XfButton(if (busy) "处理中…" else if (file != null) "安装已校验更新" else if (release != null) "下载 ${release!!.version}" else "检查更新", {
            if (file != null) { updater.install(file!!); message = "如需授权安装，请完成系统设置后再次点击安装。" }
            else scope.launch {
                busy = true
                try {
                    if (release == null) { val next = updater.check(); if(next.version == BuildConfig.VERSION_NAME) message = "当前已是最新版本 ${next.version}" else { release = next; message = "发现版本 ${next.version}，约 ${next.size / 1024 / 1024} MiB" } }
                    else { file = updater.download(release!!) { value -> message = "正在下载 $value%" }; message = "安装包及签名已校验，可覆盖更新并保留数据。" }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "更新失败，请重试" }
                finally { busy = false }
            }
        }, enabled = !busy, kind = XfButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
    }
}
