package com.singu.xfriend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** VM and foreground service MUST obtain Store here; never construct a second mutable Store. */
object FriendStoreRegistry {
    private val lock = Any()
    private val stores = mutableMapOf<String, Store>()
    private val authorizations = mutableMapOf<String, () -> Boolean>()
    private val generations = mutableMapOf<String, Long>()

    suspend fun get(context: Context, scope: String): Store = withContext(Dispatchers.IO) {
        FriendRunIds.scope(scope)
        synchronized(lock) { stores.getOrPut(scope) { Store(context.applicationContext, scope) } }
    }

    /** Bind the live account's scope predicate, not a captured Boolean or token. No credentials retained.
     * Parent binds after login and calls cancelScope before account logout/space switch.
     * Missing binding fails closed (including after process death); background restart cannot resurrect auth.
     */
    fun bindAuthorization(scope: String, isAuthorized: () -> Boolean) {
        FriendRunIds.scope(scope)
        synchronized(lock) { authorizations[scope] = isAuthorized; generations[scope] = (generations[scope] ?: 0) + 1 }
    }
    internal fun generation(scope: String): Long = synchronized(lock) { generations[scope] ?: 0 }
    internal fun authorized(scope: String, generation: Long): Boolean = synchronized(lock) {
        generations[scope] == generation && runCatching { authorizations[scope]?.invoke() == true }.getOrDefault(false)
    }
    /** Linearizes a VM write with logout/rebind, including the actual disk commit. */
    internal fun <T> withAuthorization(scope: String, generation: Long, action: () -> T): T = synchronized(lock) {
        if (!authorized(scope, generation)) throw kotlinx.coroutines.CancellationException("账号授权已结束")
        action()
    }
    internal fun revoke(scope: String) {
        FriendRunIds.scope(scope)
        synchronized(lock) { authorizations.remove(scope); generations[scope] = (generations[scope] ?: 0) + 1 }
    }
    /** Only call after the old scope worker has stopped. cancelScope does this automatically. */
    fun clear(scope: String) {
        FriendRunIds.scope(scope)
        synchronized(lock) { stores.remove(scope); authorizations.remove(scope); generations[scope] = (generations[scope] ?: 0) + 1 }
    }
    internal fun clearIfGeneration(scope: String, expected: Long) {
        synchronized(lock) {
            if (generations[scope] == expected) {
                stores.remove(scope); authorizations.remove(scope)
                generations[scope] = expected + 1
            }
        }
    }
}
