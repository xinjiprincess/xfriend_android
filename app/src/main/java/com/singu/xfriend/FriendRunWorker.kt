package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal interface FriendRunExecution {
    suspend fun preflight(resourceId: String, team: Boolean)
    suspend fun run(resourceId: String, input: String, team: Boolean, onStatus: (String) -> Unit, onDelta: (String) -> Unit): String
}

/** Raw deltas append exactly once. Persist at most every 400ms, always flush on exit.
 * Never auto-replays tools. Runtime owns the frozen model/resource snapshot under the Store lock.
 */
internal class FriendRunWorker(
    private val queue: FriendRunQueue,
    private val execution: FriendRunExecution,
    private val authorized: () -> Boolean,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun execute(run: FriendRun) {
        val job = currentCoroutineContext()
        val partial = StringBuilder(run.partial)
        val partialLock = Any()
        var needsResumeSeparator = run.checkpoint != null && run.partial.isNotEmpty()
        var lastWrite = now()
        fun guard() {
            job.ensureActive()
            if (!authorized()) throw CancellationException("authorization_ended")
            if (queue.runs().none { it.id == run.id && it.state == "running" }) throw CancellationException("cancelled")
        }
        fun flush(force: Boolean = false) {
            guard()
            val clock = now()
            if (force || clock - lastWrite >= 400) {
                if (!queue.checkpoint(run.id, partial.toString())) throw CancellationException("cancelled")
                lastWrite = clock
            }
        }
        try {
            guard()
            execution.preflight(run.resourceId, run.team)
            guard()
            val deadline = run.deadlineAt.takeIf { it > 0 } ?: (System.currentTimeMillis() + SubagentPolicy.ROOT_TIMEOUT_MILLIS)
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw FriendModelFailure("AGENT_DEADLINE", "任务执行时间预算已到期，请检查已完成的结果。")
            val answer = withTimeout(remaining) { execution.run(run.resourceId, run.input, run.team,
                onStatus = { synchronized(partialLock) { flush() } },
                onDelta = { delta -> synchronized(partialLock) {
                    guard()
                    if (delta.isNotEmpty() && needsResumeSeparator) {
                        partial.append("\n\n---\n从检查点继续的新输出\n\n")
                        needsResumeSeparator = false
                    }
                    partial.append(delta); flush()
                } }) }
            guard()
            // The final return is authoritative (team summary may differ from concatenated member deltas).
            queue.finish(run.id, answer, "completed", errorMessage = "已完成")
        } catch (e: TimeoutCancellationException) {
            val review = friendCheckpointNeedsReview(queue.runs().firstOrNull { it.id == run.id }?.checkpoint)
            synchronized(partialLock) { queue.finish(run.id, partial.toString(), if (review) "requires_review" else "failed",
                if (review) "tool_effect_uncertain" else "AGENT_DEADLINE",
                if (review) "任务已超时；工具操作结果需要核查，不能自动重放。" else "任务执行时间预算已到期，已停止请求并保留结果。") }
        } catch (e: CancellationException) {
            if (!authorized()) queue.cancel(run.id)
            val saved = queue.runs().firstOrNull { it.id == run.id }
            val review = friendCheckpointNeedsReview(saved?.checkpoint)
            synchronized(partialLock) { queue.finish(run.id, partial.toString(), if (review) "requires_review" else "interrupted",
                if (review) "tool_effect_uncertain" else "execution_interrupted",
                if (review) "工具操作结果不确定，需要核查，不能自动重放。" else "运行已中断；有检查点时可继续，也可明确重新运行。") }
            throw e
        } catch (e: Exception) {
            synchronized(partialLock) {
                if (e is FriendRunNeedsReview || friendCheckpointNeedsReview(queue.runs().firstOrNull { it.id == run.id }?.checkpoint)) {
                    queue.finish(run.id, partial.toString(), "requires_review", "tool_effect_uncertain", "工具操作结果不确定，需要核查，不能自动重放。")
                } else if (e is FriendModelFailure) {
                    queue.finish(run.id, partial.toString(), "failed", e.code, e.userMessage, e.diagnosticId)
                } else {
                    val failure = friendRunFailure(e)
                    queue.finish(run.id, partial.toString(), "failed", failure.first, failure.second)
                }
            }
        }
    }
}

/** Exception text may contain URLs, input, headers or keys. Persist only fixed safe messages. */
internal fun friendRunFailure(error: Exception): Pair<String, String> = when (error) {
    is UnknownHostException -> "dns_failed" to "无法解析模型服务地址，请检查地址和网络设置。"
    is SocketTimeoutException -> "request_timeout" to "模型服务响应超时；可以创建新任务重试。"
    is SSLException -> "tls_failed" to "无法安全连接模型服务，请检查证书和设备时间。"
    is IOException -> "network_failed" to "连接模型服务失败，请检查网络和接口地址。"
    is IllegalArgumentException -> "preflight_failed" to "任务配置不满足运行要求，请检查模型、专家和工具配置。"
    else -> "execution_failed" to "任务未完成，请检查运行配置；诊断编号可用于排查。"
}
