package com.singu.xfriend

/** Selects the frozen task target while reusing the same worker, tools and checkpoint lifecycle. */
internal class AgentRunExecution(
    private val runtime: AgentRuntime,
    private val localRoomTurn: LocalRoomTurn? = null,
) : FriendRunExecution {
    override suspend fun preflight(resourceId: String, team: Boolean) {
        localRoomTurn?.let { runtime.preflightGroup(it.roomId, it.memberIds) } ?: runtime.preflight(resourceId, team)
    }

    override suspend fun run(resourceId: String, input: String, team: Boolean, onStatus: (String) -> Unit, onDelta: (String) -> Unit): String =
        localRoomTurn?.let { runtime.runGroup(it.roomId, it.memberIds, input, onStatus, onDelta) }
            ?: if (team) runtime.runTeam(resourceId, input, onStatus, onDelta) else runtime.run(resourceId, input, onStatus, onDelta)
}
