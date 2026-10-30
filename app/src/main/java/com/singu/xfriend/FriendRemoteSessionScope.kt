package com.singu.xfriend

import java.security.MessageDigest

/** Local identity only. Neither this fingerprint nor a phone credential is sent as host identity. */
internal fun friendRemoteSessionScope(session: FriendRemoteSession): String =
    MessageDigest.getInstance("SHA-256")
        .digest((companionOrigin(session.origin) + "\u0000" + session.cookie).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun FriendRemoteRun.belongsTo(session: FriendRemoteSession) =
    sessionScope.isNotBlank() && sessionScope == friendRemoteSessionScope(session)

internal val FriendRemoteRun.historyKey get() = "$sessionScope\u0000$clientRequestId"

/** Pair changes archive the old pointer; unbound uncertain submissions can never acquire a new actor. */
internal fun FriendRemoteSaved.forSession(next: FriendRemoteSession): FriendRemoteSaved {
    val records = (listOfNotNull(run) + history).distinctBy { it.historyKey }
    val current = run?.takeIf {
        session == next && (it.belongsTo(next) || it.sessionScope.isBlank() && it.runId.isNotBlank())
    }
    return FriendRemoteSaved(next, current, records)
}

internal fun FriendRemoteSaved.withRun(next: FriendRemoteRun, select: Boolean = true): FriendRemoteSaved {
    val records = (listOf(next) + history.filterNot {
        it.historyKey == next.historyKey || it.sessionScope.isBlank() && it.runId.isNotBlank() &&
            it.runId == next.runId && it.clientRequestId == next.clientRequestId
    })
    // Keep isolated records across pair changes; retain the existing current-session history bound.
    val recent = records.filter { it.sessionScope == next.sessionScope }.take(100)
    val isolated = records.filter { it.sessionScope != next.sessionScope }
    return copy(run = if (select || run?.historyKey == next.historyKey) next else run, history = recent + isolated)
}

internal fun FriendRemoteSaved.observableRuns(): List<FriendRemoteRun> {
    val current = session ?: return emptyList()
    return (listOfNotNull(run) + history).filter { it.belongsTo(current) && !it.terminal && it.runId.isNotBlank() }
        .distinctBy { it.historyKey }
}
