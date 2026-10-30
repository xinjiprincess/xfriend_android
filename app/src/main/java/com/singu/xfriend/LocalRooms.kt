package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject

data class LocalRoom(
    val id: String,
    val name: String,
    val memberIds: List<String>,
    val messages: List<LocalRoomMessage> = emptyList(),
    val draft: String = "",
) {
    init {
        localRoomId(id)
        require(name.isNotBlank() && name.length <= 80) { "群名称需为 1–80 个字符" }
        localRoomMembers(memberIds)
        require(messages.map { it.id }.distinct().size == messages.size) { "群消息编号重复" }
        require(draft.length <= 12000) { "群聊草稿超过长度限制" }
    }
}

data class LocalRoomMessage(
    val id: String,
    val role: String,
    val content: String,
    val speakerId: String? = null,
    val speakerName: String? = null,
    val created: Long = System.currentTimeMillis(),
) {
    init {
        require(id.isNotBlank() && id.length <= 200) { "无效群消息编号" }
        require(role in setOf("user", "assistant") && content.isNotBlank()) { "无效群消息" }
        if (role == "assistant") {
            require(!speakerId.isNullOrBlank() && !speakerName.isNullOrBlank()) { "专家发言缺少成员信息" }
            localRoomId(speakerId)
        }
    }
}

/** The selected speakers are frozen before a run enters the durable task queue. */
data class LocalRoomTurn(val roomId: String, val memberIds: List<String>) {
    init { localRoomId(roomId); localRoomMembers(memberIds) }
}

/** A restored one-person request must never silently become a request to every group member. */
internal fun localRoomReplyTarget(room: LocalRoom, turn: LocalRoomTurn): String? = when {
    turn.roomId != room.id -> null
    turn.memberIds == room.memberIds -> ""
    turn.memberIds.size == 1 && turn.memberIds.single() in room.memberIds -> turn.memberIds.single()
    else -> null
}

internal fun localRoomVisibleRun(roomId: String, runs: List<FriendRun>): FriendRun? = runs
    .filter { it.localRoomTurn?.roomId == roomId }
    .maxWithOrNull(compareBy<FriendRun> { it.active }.thenBy { it.updated }.thenBy { it.created })

private fun localRoomId(id: String) {
    require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "无效群或成员编号" }
}

private fun localRoomMembers(ids: List<String>) {
    require(ids.size in 1..8 && ids.distinct().size == ids.size) { "群需包含 1–8 位不同专家" }
    ids.forEach(::localRoomId)
}

internal object LocalRoomCodec {
    fun encode(rooms: List<LocalRoom>): JSONArray = JSONArray(rooms.map { room ->
        JSONObject().put("id", room.id).put("name", room.name).put("memberIds", JSONArray(room.memberIds))
            .put("draft", room.draft).put("messages", JSONArray(room.messages.map { message ->
                JSONObject().put("id", message.id).put("role", message.role).put("content", message.content)
                    .put("speakerId", message.speakerId ?: JSONObject.NULL)
                    .put("speakerName", message.speakerName ?: JSONObject.NULL).put("created", message.created)
            }))
    })

    fun decode(value: JSONArray?): List<LocalRoom> = value?.objects()?.map { room ->
        LocalRoom(room.getString("id"), room.getString("name"), room.getJSONArray("memberIds").strings(),
            room.optJSONArray("messages")?.objects()?.map { message ->
                LocalRoomMessage(message.getString("id"), message.getString("role"), message.getString("content"),
                    if (message.isNull("speakerId")) null else message.getString("speakerId"),
                    if (message.isNull("speakerName")) null else message.getString("speakerName"), message.getLong("created"))
            }.orEmpty(), room.optString("draft", ""))
    }.orEmpty().also { rooms -> require(rooms.map { it.id }.distinct().size == rooms.size) { "群编号重复" } }
}
