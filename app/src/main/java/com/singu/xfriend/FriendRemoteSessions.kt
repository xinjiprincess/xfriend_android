package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** In-process generation barrier prevents late callbacks from resurrecting a logged-out session. */
internal object FriendRemoteSessions {
    private data class Entry(var generation: Long = 0, var clearing: Boolean = false, val jobs: MutableSet<Job> = mutableSetOf())
    private val entries = mutableMapOf<String, Entry>()
    @Synchronized fun generation(scope: String): Long = entries.getOrPut(scope) { Entry() }.generation
    @Synchronized fun <T> access(scope: String, generation: Long, block: () -> T): T {
        val entry = entries.getOrPut(scope) { Entry() }
        if (entry.clearing || entry.generation != generation) throw CancellationException("Remote session logged out")
        return block()
    }
    fun register(scope: String, job: Job) {
        synchronized(this) {
            val entry = entries.getOrPut(scope) { Entry() }
            if (entry.clearing) { job.cancel(); return }
            entry.jobs.add(job)
        }
        job.invokeOnCompletion { synchronized(this) { entries[scope]?.jobs?.remove(job) } }
    }
    fun invalidate(scope: String): Long {
        val jobs: List<Job>
        val generation: Long
        synchronized(this) {
            val entry = entries.getOrPut(scope) { Entry() }
            entry.generation++; entry.clearing = true
            generation = entry.generation; jobs = entry.jobs.toList(); entry.jobs.clear()
        }
        jobs.forEach { it.cancel() }
        return generation
    }
    @Synchronized fun cleared(scope: String, generation: Long) {
        entries[scope]?.takeIf { it.generation == generation }?.clearing = false
    }
    @Synchronized fun <T> cleanup(scope: String, generation: Long, block: () -> T): T? {
        val entry = entries[scope] ?: return null
        if (entry.generation != generation || !entry.clearing) return null
        val value = block()
        entry.clearing = false
        return value
    }
}
