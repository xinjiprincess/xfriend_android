package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class AgentRuntimeCheckpointTest {
    private class State(override val workspaceRoot: File): RuntimeState {
        override val experts=listOf(Expert("a","A","first"),Expert("b","B","second"))
        override val teams=listOf(Team("team","Team",listOf("a","b")))
        override val skills=emptyList<Skill>();override val knowledge=emptyList<Knowledge>()
        override var messages=emptyMap<String,List<Message>>()
        override var runtimeHistorySteps=emptySet<String>()
        override val baseUrl="https://example.test/v1"
        override var model="model";override var apiKey="not-a-real-key"
        override fun save() {}
    }
    private class Memory: RuntimeCheckpoint {
        var saved:JSONObject?=null
        var before:((JSONObject)->Unit)?=null
        var after:((JSONObject)->Unit)?=null
        override suspend fun load()=saved?.let{JSONObject(it.toString())}
        override suspend fun save(checkpoint:JSONObject) {
            before?.invoke(checkpoint)
            saved=JSONObject(checkpoint.toString())
            after?.invoke(checkpoint)
        }
    }
    private fun json(request:Request):JSONObject {val b=okio.Buffer();request.body!!.writeTo(b);return JSONObject(b.readUtf8())}
    private fun answer(text:String)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("role","assistant").put("content",text))))
    private fun write()=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","tool_calls").put("message",JSONObject().put("role","assistant")
        .put("reasoning_content","preserved private reasoning").put("tool_calls",JSONArray().put(JSONObject().put("id","write-call").put("type","function")
            .put("function",JSONObject().put("name","write_file").put("arguments","{\"path\":\"proof.txt\",\"content\":\"planned\"}")))))))
    private fun node(value:JSONObject)=value.getJSONObject("steps").optJSONObject("expert")

    @Test fun crashAfterWriteUsesFileHashAndNeverRewritesSuccessfulEffect()= runBlocking {
        val root=java.nio.file.Files.createTempDirectory("checkpoint-write").toFile()
        try {
            val state=State(root);val memory=Memory();var requests=0
            memory.before={ cp ->if(node(cp)?.optInt("tool_index")==1) {memory.before=null;throw IOException("simulated crash before tool checkpoint commit")} }
            val executor:suspend(Request)->JSONObject={ req ->
                if(requests++==0) write() else {
                    val m=json(req).getJSONArray("messages")
                    assertEquals("tool",m.getJSONObject(m.length()-1).getString("role"))
                    assertEquals("preserved private reasoning",m.getJSONObject(m.length()-2).getString("reasoning_content"))
                    answer("done")
                }
            }
            try {AgentRuntime(state,checkpoint=memory,requestExecutor=executor).run("a","write",{});fail()}catch(_:IOException){}
            val file=File(root,"a/proof.txt");assertEquals("planned",file.readText())
            assertTrue(file.setLastModified(1_600_000_000_000L));val stamp=file.lastModified()
            assertEquals("done",AgentRuntime(state,checkpoint=memory,requestExecutor=executor).run("a","write",{}))
            assertEquals(stamp,file.lastModified());assertEquals(2,requests)
            assertEquals(listOf(Message("user","write"),Message("assistant","done")),state.messages["a"]?.map { it.copy(id = "") })
            assertFalse(memory.saved.toString().contains("not-a-real-key"))
            assertEquals("done",AgentRuntime(state,checkpoint=memory,requestExecutor={fail("No completed task replay");answer("bad")}).run("a","write",{}))
        }finally{root.deleteRecursively()}
    }
    @Test fun pendingWriteWithNoMatchingEffectRequiresReviewRatherThanReplay()= runBlocking {
        val root=java.nio.file.Files.createTempDirectory("checkpoint-pending").toFile()
        try {
            val state=State(root);val memory=Memory()
            memory.after={ cp ->if(node(cp)?.has("pending")==true){memory.after=null;throw IOException("crash before write")} }
            try {AgentRuntime(state,checkpoint=memory){write()}.run("a","write",{});fail()}catch(_:IOException){}
            assertFalse(File(root,"a/proof.txt").exists())
            try {AgentRuntime(state,checkpoint=memory){fail("No model replay");answer("bad")}.run("a","write",{});fail()}
            catch(e:FriendModelFailure){assertEquals("CHECKPOINT_REVIEW",e.code)}
            assertFalse(File(root,"a/proof.txt").exists());assertEquals("requires_review",memory.saved!!.getString("status"))
        }finally{root.deleteRecursively()}
    }
    @Test fun resumeRetainsCompletedToolResultAndAllowsKeyRotationButNotModelChange()=runBlocking {
        val root=java.nio.file.Files.createTempDirectory("checkpoint-config").toFile()
        try {
            val state=State(root);val memory=Memory();var calls=0
            try {AgentRuntime(state,checkpoint=memory){if(calls++==0)write() else throw IOException("offline")}.run("a","write",{});fail()}catch(_:IOException){}
            state.model="changed"
            try {AgentRuntime(state,checkpoint=memory){fail("No changed provider replay");answer("bad")}.run("a","write",{});fail()}
            catch(e:FriendModelFailure){assertEquals("CHECKPOINT_CONFIG_CHANGED",e.code)}
            state.model="model";state.apiKey="rotated-test-key"
            assertEquals("done",AgentRuntime(state,checkpoint=memory){req->
                assertEquals("Bearer rotated-test-key",req.header("Authorization"))
                val messages=json(req).getJSONArray("messages");assertEquals("tool",messages.getJSONObject(messages.length()-1).getString("role"))
                answer("done")
            }.run("a","write",{}))
            assertEquals(2,state.messages.getValue("a").size)
        }finally{root.deleteRecursively()}
    }
    @Test fun teamResumeSkipsCompletedMembersAndDoesNotDuplicateHistory()=runBlocking {
        val root=java.nio.file.Files.createTempDirectory("checkpoint-team").toFile()
        try {
            val state=State(root);val memory=Memory();var calls=0
            try {AgentRuntime(state,checkpoint=memory){if(calls++==0)answer("A done") else throw IOException("offline")}.runTeam("team","task",{});fail()}catch(_:IOException){}
            assertEquals(1,memory.saved!!.getInt("team_index"))
            val result=AgentRuntime(state,checkpoint=memory){req ->
                val body=json(req);assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").startsWith("second"))
                answer("B done")
            }.runTeam("team","task",{})
            assertTrue(result.contains("A done"));assertTrue(result.contains("B done"))
            assertEquals(3,state.messages.getValue("team:team").size);assertEquals(2,state.messages.getValue("a").size);assertEquals(2,state.messages.getValue("b").size)
        }finally{root.deleteRecursively()}
    }
    @Test fun crashAfterHistoryCommitBeforeCheckpointDoesNotAppendAssistantTwice()=runBlocking {
        val root=java.nio.file.Files.createTempDirectory("checkpoint-history").toFile()
        try {
            val state=State(root);val memory=Memory();var calls=0
            memory.before={ cp ->if(node(cp)?.optBoolean("done")==true){memory.before=null;throw IOException("crash after history save")} }
            try {AgentRuntime(state,checkpoint=memory){calls++;answer("done")}.run("a","task",{});fail()}catch(_:IOException){}
            assertEquals(2,state.messages.getValue("a").size)
            assertEquals("done",AgentRuntime(state,checkpoint=memory){fail("Reuse complete model result");answer("bad")}.run("a","task",{}))
            assertEquals(1,calls);assertEquals(2,state.messages.getValue("a").size)
        }finally{root.deleteRecursively()}
    }
}
