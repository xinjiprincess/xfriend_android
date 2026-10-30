package com.singu.xfriend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Serialize rotating refresh tokens across the main agent, its children and the settings page. */
internal class FriendProviderSession(
    private val scope: String,
    private val generation: Long,
    private val store: Store,
    private val accounts: FriendProviderAccounts = FriendProviderAccounts(),
) {
    suspend fun resolve(snapshot: FriendModelConnection): FriendModelConnection =
        locks.getOrPut("$scope:${snapshot.id}") { Mutex() }.withLock {
            fun current(): FriendModelConnection = FriendStoreRegistry.withAuthorization(scope, generation) {
                synchronized(store) {
                    store.modelConnections.firstOrNull { it.id == snapshot.id && it.enabled && it.generation == snapshot.generation }
                        ?: throw FriendModelFailure("CONFIG_PROVIDER_CHANGED", "供应商连接已更改或移除，请重新发起任务")
                }
            }
            val before = current()
            val refreshed = accounts.refresh(before)
            if (!FriendStoreRegistry.authorized(scope, generation)) throw CancellationException("authorization_ended")
            current() // Configuration may have changed while the refresh request was in flight.
            if (refreshed.credentials != before.credentials) {
                FriendStoreRegistry.withAuthorization(scope, generation) {
                    synchronized(store) {
                        val live = current()
                        if (live.credentials != before.credentials) throw FriendModelFailure("MODEL_AUTH_CHANGED", "账号授权已更新，请重新发起请求")
                        val previous = store.modelConnections
                        store.modelConnections = previous.map { if (it.id == live.id) it.copy(credentials = refreshed.credentials.toMap()) else it }
                        try { store.save() } catch (failure: Exception) { store.modelConnections = previous; throw failure }
                    }
                }
            }
            snapshot.copy(credentials = refreshed.credentials.toMap())
        }

    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
