package com.singu.xfriend

import kotlinx.coroutines.Job

/** All changes (including repository stale recovery) use this monitor. In-memory only by design. */
internal object FriendRunLiveness {
    val lock = Any()
    private val workerJobs = mutableMapOf<String, LinkedHashMap<String, Job>>()
    private const val LEGACY = "legacy"

    fun active(scope: String): Boolean = synchronized(lock) { workerJobs[scope]?.isNotEmpty() == true }
    /** Existing callers remain exclusive across the complete account scope. */
    fun reserve(scope: String, job: Job): Boolean = synchronized(lock) {
        if (active(scope)) false else { workerJobs[scope] = linkedMapOf(LEGACY to job); true }
    }
    fun remove(scope: String, job: Job) = synchronized(lock) {
        workerJobs[scope]?.let { jobs ->
            jobs.entries.removeAll { it.value === job }
            if (jobs.isEmpty()) workerJobs.remove(scope)
        }
        Unit
    }
    fun job(scope: String): Job? = synchronized(lock) { workerJobs[scope]?.values?.firstOrNull() }

    fun reserveRun(scope: String, runId: String, job: Job): Boolean = synchronized(lock) {
        FriendRunIds.run(runId)
        val jobs = workerJobs.getOrPut(scope) { linkedMapOf() }
        if (LEGACY in jobs || runId in jobs || jobs.size >= SubagentPolicy.MAX_CONCURRENT) false
        else { jobs[runId] = job; true }
    }
    fun job(scope: String, runId: String): Job? = synchronized(lock) { workerJobs[scope]?.get(runId) }
    fun jobs(scope: String): List<Job> = synchronized(lock) { workerJobs[scope]?.values?.toList().orEmpty() }
    fun removeRun(scope: String, runId: String, job: Job) = synchronized(lock) {
        workerJobs[scope]?.let { jobs ->
            if (jobs[runId] === job) jobs.remove(runId)
            if (jobs.isEmpty()) workerJobs.remove(scope)
        }
        Unit
    }
}
