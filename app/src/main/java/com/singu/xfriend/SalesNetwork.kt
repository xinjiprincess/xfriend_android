package com.singu.xfriend

import java.io.IOException
import java.net.*
import javax.net.ssl.SSLException

internal fun salesNetworkError(error: IOException): String = when (error) {
    is UnknownHostException -> "无法解析服务器域名，请检查模型地址、DNS 或网络；资料已保存，可稍后重试"
    is SSLException -> "HTTPS 证书校验失败，请检查服务器证书和手机时间；不会跳过证书验证"
    is SocketTimeoutException -> "模型响应超时，任务进度已保留；请到任务中心重试或检查模型服务"
    is ConnectException -> "无法连接模型服务器，请检查服务地址、端口及服务器是否在线"
    is java.io.InterruptedIOException -> "网络请求超时或已中断，已完成的分段会保留，可稍后重试"
    else -> "连接中断，可能是网络切换或服务断开；资料已保存，请到任务中心重试"
}
