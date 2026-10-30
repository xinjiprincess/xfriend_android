package com.singu.xfriend

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class FriendRunRegistryTest {
    @Test fun vmRecoveryDoesNotMarkTrulyActiveServiceRunInterrupted() {
        val scope = "e".repeat(64); val q = FriendRunQueue(FriendRunMemory(), Any())
        q.enqueue("e", "i", false, "req"); val job = Job()
        synchronized(FriendRunLiveness.lock) { FriendRunLiveness.reserve(scope, job); q.claim() }
        try {
            q.recoverStale(scope)
            assertEquals("running", q.runs().single().state)
        } finally { FriendRunLiveness.remove(scope, job); job.cancel() }
        q.recoverStale(scope)
        assertEquals("interrupted", q.runs().single().state)
    }
    @Test fun authorizationIsLiveNotCapturedAtLogin() {
        val scope = "a".repeat(64); var loggedIn = true
        FriendStoreRegistry.bindAuthorization(scope) { loggedIn }
        val generation = FriendStoreRegistry.generation(scope)
        assertTrue(FriendStoreRegistry.authorized(scope, generation))
        loggedIn = false; assertFalse(FriendStoreRegistry.authorized(scope, generation))
        FriendStoreRegistry.clear(scope)
    }
    @Test fun logoutGenerationInvalidatesOldWorkerEvenAfterRelogin() {
        val scope = "b".repeat(64)
        FriendStoreRegistry.bindAuthorization(scope) { true }; val old = FriendStoreRegistry.generation(scope)
        FriendStoreRegistry.revoke(scope); val revoked = FriendStoreRegistry.generation(scope)
        assertFalse(FriendStoreRegistry.authorized(scope, old))
        FriendStoreRegistry.bindAuthorization(scope) { true }; val fresh = FriendStoreRegistry.generation(scope)
        FriendStoreRegistry.clearIfGeneration(scope, revoked)
        assertTrue(FriendStoreRegistry.authorized(scope, fresh)); assertFalse(FriendStoreRegistry.authorized(scope, old))
        FriendStoreRegistry.clear(scope)
    }
    @Test fun missingOrThrowingAuthorizationFailsClosed() {
        val scope = "c".repeat(64); FriendStoreRegistry.clear(scope)
        assertFalse(FriendStoreRegistry.authorized(scope, FriendStoreRegistry.generation(scope)))
        FriendStoreRegistry.bindAuthorization(scope) { error("expired") }
        assertFalse(FriendStoreRegistry.authorized(scope, FriendStoreRegistry.generation(scope)))
        FriendStoreRegistry.clear(scope)
    }
    @Test fun liveScopeReservationCannotBeOverwrittenAndLateOldCleanupCannotClearNewJob() {
        val scope = "d".repeat(64); val a = Job(); val b = Job()
        assertTrue(FriendRunLiveness.reserve(scope, a)); assertTrue(FriendRunLiveness.active(scope))
        assertFalse(FriendRunLiveness.reserve(scope, b))
        FriendRunLiveness.remove(scope, b); assertTrue(FriendRunLiveness.active(scope))
        FriendRunLiveness.remove(scope, a); assertFalse(FriendRunLiveness.active(scope))
        assertTrue(FriendRunLiveness.reserve(scope, b)); FriendRunLiveness.remove(scope, a)
        assertEquals(b, FriendRunLiveness.job(scope))
        FriendRunLiveness.remove(scope, b); a.cancel(); b.cancel()
    }
}
