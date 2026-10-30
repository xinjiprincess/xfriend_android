package com.singu.xfriend

import kotlinx.coroutines.Job

/** Called on the independent IO control scope, including when a lazy worker never entered its body. */
internal fun completeFriendRun(queue: FriendRunQueue, scope: String, runId: String, job: Job, signal: () -> Unit) {
    try {
        synchronized(FriendRunLiveness.lock) {
            // A delayed old completion must never change a replacement worker's durable state.
            if (FriendRunLiveness.job(scope, runId) !== job) return
            try {
                val saved = queue.runs().firstOrNull { it.id == runId }
                if (saved?.state == "running") {
                    val review = friendCheckpointNeedsReview(saved.checkpoint)
                    queue.finish(runId, saved.partial, if (review) "requires_review" else "interrupted",
                        if (review) "tool_effect_uncertain" else "execution_interrupted",
                        if (review) "工具操作结果不确定，请核查后处理，不能自动重放。" else "运行已中断；记录与检查点已保留。")
                }
            } finally { FriendRunLiveness.removeRun(scope, runId, job) }
        }
    } finally { signal() }
}

/** Queue handles must already exist: repository construction takes locks in the opposite order. */
internal fun friendRunSchedulerIdle(queues: Map<String, FriendRunQueue>): Boolean = synchronized(FriendRunLiveness.lock) {
    queues.all { (scope, queue) -> !FriendRunLiveness.active(scope) && queue.runs().none { it.state == "queued" } }
}
