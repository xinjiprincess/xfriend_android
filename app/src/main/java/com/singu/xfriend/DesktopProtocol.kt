package com.singu.xfriend

import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal data class DesktopHandshake(val entryPath: String)

internal fun parseDesktopHandshake(body: String): DesktopHandshake {
    val status = JSONObject(body)
    require(status.optString("application") == "xfriend" && status.opt("protocol") == 1) { "电脑版连接协议不兼容，请升级电脑版或连接桥" }
    when (status.optString("state")) {
        "ready" -> Unit
        "login_required" -> error("请先在电脑版登录账号，再连接手机")
        else -> error("电脑版尚未就绪，请确认电脑版和连接服务已启动")
    }
    // Version 1 uses a fixed entry point so a discovery response cannot redirect credentials.
    require(status.optString("entry_path") == "/chat") { "电脑版页面入口不兼容" }
    return DesktopHandshake("/chat")
}

internal fun probeDesktop(origin: String): DesktopHandshake {
    val address = companionOrigin(origin)
    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(8, TimeUnit.SECONDS).build()
    return client.newCall(Request.Builder().url("$address/mobile/status").build()).execute().use { response ->
        check(response.isSuccessful) { if (response.code == 404) "电脑版尚未提供手机连接接口，请升级电脑版或启动连接桥" else "电脑连接服务暂不可用（${response.code}）" }
        val bytes = response.body?.byteStream()?.use { input ->
            val buffer = ByteArray(65537); var count = 0
            while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
            buffer.copyOf(count)
        } ?: byteArrayOf()
        require(bytes.size <= 65536) { "电脑版连接信息异常" }
        parseDesktopHandshake(bytes.toString(Charsets.UTF_8))
    }
}
