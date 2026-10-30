package com.singu.xfriend

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep confirmed edits queued while foreground work is busy, in submission order. */
internal class CustomerWriteQueue {
    private val mutex = Mutex()
    private var preview: SalesData? = null
    private var pending = 0

    /** Called on the UI thread, so a quick edit-then-revert is compared with the queued edit. */
    fun prepare(current: SalesData?, action: (SalesData) -> SalesData): CustomerWrite? {
        val before = preview ?: requireNotNull(current) { "销售空间尚未加载，请稍后重新保存" }
        val next = action(before)
        if (next == before) return null
        preview = next
        pending += 1
        return CustomerWrite(before, next)
    }

    fun finished() {
        pending -= 1
        if (pending == 0) preview = null
    }

    suspend fun <T> commit(awaitIdle: suspend () -> Unit, write: suspend () -> T): T = mutex.withLock {
        awaitIdle()
        write()
    }
}

internal data class CustomerWrite(val before: SalesData, val next: SalesData)
