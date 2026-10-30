package com.singu.xfriend

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FriendModelClientTest {
    private fun chunk(text: String, finish: String? = null): String = "data: " + JSONObject().put("choices", JSONArray().put(
        JSONObject().put("index", 0).put("delta", JSONObject().put("content", text)).put("finish_reason", finish ?: JSONObject.NULL))) + "\n\n"
    private fun sse(value: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(value)
    private fun request(server: MockWebServer) = Request.Builder().url(server.url("/v1/chat/completions"))
        .post("{}".toRequestBody("application/json".toMediaType())).build()
    private fun callPart(index: Int, id: String?, name: String?, args: String): JSONObject = JSONObject().put("index", index)
        .also { if (id != null) it.put("id", id) }.put("function", JSONObject().put("arguments", args).also { if (name != null) it.put("name", name) })
    private fun toolEvent(vararg parts: JSONObject, finish: String? = null): String = "data: " + JSONObject().put("choices", JSONArray().put(
        JSONObject().put("index", 0).put("delta", JSONObject().put("tool_calls", JSONArray(parts.toList())))
            .put("finish_reason", finish ?: JSONObject.NULL))) + "\n\n"

    @Test fun normalizesOnlyOneCompletionsSuffixAndRejectsUnsafeUrls() {
        assertEquals("https://example.test/v1", normalizeFriendModelBaseUrl(" https://example.test/v1/chat/completions/ "))
        assertEquals("https://example.test", normalizeFriendModelBaseUrl("https://example.test/"))
        assertEquals("https://example.test/chat/completions", normalizeFriendModelBaseUrl("https://example.test/chat/completions/chat/completions"))
        for (raw in listOf("http://example.test", "https://u:p@example.test", "https://example.test?a=secret", "https://example.test/#fragment", "invalid")) {
            try { normalizeFriendModelBaseUrl(raw); fail(raw) } catch (e: FriendModelFailure) { assertEquals("CONFIG_URL", e.code) }
        }
    }
    @Test fun realSseDeliversRawUnicodeDeltas() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(": heartbeat\n\n" + chunk("你") + chunk("好", "stop") + "data: [DONE]\n\n"))
            val deltas = mutableListOf<String>()
            val result = FriendModelTransport().complete(request(server), deltas::add)
            assertEquals(listOf("你", "好"), deltas)
            assertEquals("你好", result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        }
    }
    @Test fun interleavedToolArgumentsAreReassembledByIndex() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(toolEvent(callPart(1,"b","read_file","{\"path\":"),callPart(0,"a","write_file","{\"path\":\"a.txt\",")) +
                toolEvent(callPart(0,null,null,"\"content\":\"你好\"}"),callPart(1,null,null,"\"a.txt\"}"), finish="tool_calls") + "data: [DONE]\n\n"))
            val result = FriendModelTransport().complete(request(server)) {}
            val calls = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls")
            assertEquals("a", calls.getJSONObject(0).getString("id"))
            assertEquals("你好", JSONObject(calls.getJSONObject(0).getJSONObject("function").getString("arguments")).getString("content"))
            assertEquals("b", calls.getJSONObject(1).getString("id"))
        }
    }
    @Test fun httpErrorsNeverIncludeProviderResponseOrSecrets() = runBlocking {
        MockWebServer().use { server ->
            for (code in listOf(400,401,402,403,404,405,413,422,429,500,503)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("secret-key private-prompt"))
                try { FriendModelTransport().complete(request(server)) {}; fail("Accepted $code") } catch(e: FriendModelFailure) {
                    assertEquals("HTTP_$code",e.code); assertNotNull(e.diagnosticId)
                    assertFalse(e.toString().contains("secret-key")); assertFalse(e.toString().contains("private-prompt")); assertNull(e.cause)
                }
            }
        }
    }
    @Test fun httpFailuresExplainActionableNonNetworkCategories() {
        val expected = mapOf(400 to "参数或协议不兼容", 422 to "参数或协议不兼容", 402 to "付款",
            405 to "请求方法不兼容", 413 to "输入过大", 500 to "暂时故障", 502 to "暂时故障", 599 to "暂时故障")
        expected.forEach { (code, text) ->
            val failure = friendHttpFailure(code)
            assertEquals("HTTP_$code", failure.code)
            assertTrue(failure.userMessage, failure.userMessage.contains(text))
            assertNull(failure.cause)
            if (code < 500) assertFalse(failure.userMessage.contains("稍后重试"))
        }
        assertTrue(friendHttpFailure(400).userMessage.contains("工具开关"))
        assertTrue(friendHttpFailure(422).userMessage.contains("模型名称"))
    }

    @Test fun malformedOrInterruptedStreamDoesNotBecomeSuccess() = runBlocking {
        MockWebServer().use { server ->
            for (body in listOf("data: invalid secret-key\n\n", chunk("partial"), "data: [DONE]\n\n")) {
                server.enqueue(sse(body))
                try { FriendModelTransport().complete(request(server)) {}; fail("Accepted malformed stream") } catch(e: FriendModelFailure) {
                    assertEquals("PROTOCOL", e.code); assertFalse(e.toString().contains("secret-key"))
                }
            }
        }
    }
    @Test fun cancellationCancelsUnderlyingCallPromptly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = AtomicBoolean(false)
            val transport = FriendModelTransport(OkHttpClient.Builder().eventListener(object: EventListener() {
                override fun canceled(call: Call) { cancelled.set(true) }
            }).build())
            val job = launch(Dispatchers.IO) { transport.complete(request(server)) {}; fail("Unexpected completion") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(cancelled.get()); assertTrue(job.isCancelled)
        }
    }
    @Test fun callbackCancellationBeforeOwnerJobCancellationNeverEscapesOkHttpThread() = runBlocking {
        for (stream in listOf(true, false)) {
            MockWebServer().use { server ->
                if (stream) server.enqueue(sse(chunk("first") + chunk("later", "stop") + "data: [DONE]\n\n"))
                else server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(answer("first").toString()))
                val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable>()
                val executor = java.util.concurrent.Executors.newCachedThreadPool { task ->
                    Thread(task, "friend-callback-regression").apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.set(error) } }
                }
                val cancelled = AtomicBoolean(false)
                val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).eventListener(object: EventListener() {
                    override fun canceled(call: Call) { cancelled.set(true) }
                }).build()
                val rejection = CancellationException("authorization revoked")
                var deltas = 0
                try {
                    withTimeout(3000) {
                        FriendModelTransport(client).complete(request(server)) { deltas++; throw rejection }
                    }
                    fail("Callback cancellation must terminate the suspension")
                } catch (e: CancellationException) {
                    // Coroutine stacktrace recovery may copy exceptions across suspension boundaries.
                    assertEquals(rejection.message, e.message)
                    val chain = generateSequence<Throwable>(e) { it.cause }.take(16).toList()
                    assertTrue("Expected the callback cancellation in the cause chain", chain.any {
                        it is CancellationException && it.message == rejection.message
                    })
                }
                finally {
                    client.connectionPool.evictAll()
                    executor.shutdown()
                    assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
                }
                assertTrue(currentCoroutineContext().isActive)
                assertEquals(1, deltas)
                assertTrue("Callback cancellation must cancel the underlying call", cancelled.get())
                assertNull("Callback exception escaped the OkHttp thread", uncaught.get())
            }
        }
    }
    @Test fun ordinaryCallbackFailureCancelsCallAndReturnsSanitizedFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(chunk("first") + chunk("later", "stop") + "data: [DONE]\n\n"))
            val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val executor = java.util.concurrent.Executors.newCachedThreadPool { task ->
                Thread(task, "friend-callback-failure").apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.set(error) } }
            }
            val cancelled = AtomicBoolean(false)
            val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).eventListener(object: EventListener() {
                override fun canceled(call: Call) { cancelled.set(true) }
            }).build()
            try {
                withTimeout(3000) { FriendModelTransport(client).complete(request(server)) { throw IllegalStateException("private callback input") } }
                fail("Expected mapped failure")
            } catch (e: FriendModelFailure) {
                assertEquals("PROTOCOL",e.code); assertFalse(e.toString().contains("private callback input")); assertNull(e.cause)
            } finally {
                client.connectionPool.evictAll()
                executor.shutdown()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
            assertTrue("Callback failure must cancel the underlying call", cancelled.get())
            assertNull("Callback exception escaped the OkHttp thread", uncaught.get())
        }
    }

    @Test fun diagnosticCategoriesAreSafeAndCancellationIsPreserved() {
        val failures = listOf(UnknownHostException("key prompt") to "DNS", javax.net.ssl.SSLException("key prompt") to "TLS",
            java.net.ConnectException("key prompt") to "CONNECT", java.net.SocketTimeoutException("key prompt") to "TIMEOUT",
            IllegalArgumentException("key prompt") to "PROTOCOL")
        failures.forEach { (error, code) -> val mapped = friendModelFailure(error); assertEquals(code,mapped.code); assertFalse(mapped.toString().contains("key prompt")) }
        val cancelled = CancellationException("cancel")
        try { friendModelFailure(cancelled); fail() } catch(e: CancellationException) { assertSame(cancelled,e) }
    }

    @Test fun reasoningDeltasAreHiddenButPreservedForToolFollowup() = runBlocking {
        MockWebServer().use { server ->
            val reason = { text: String -> "data: " + JSONObject().put("choices",JSONArray().put(JSONObject().put("index",0)
                .put("delta",JSONObject().put("reasoning_content",text)))) + "\n\n" }
            server.enqueue(sse(reason("private ") + reason("reasoning") + toolEvent(callPart(0,"r","list_workspace","{}"),finish="tool_calls") + "data: [DONE]\n\n"))
            server.enqueue(sse(chunk("visible", "stop") + "data: [DONE]\n\n"))
            val client=OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build()
            val dir=java.nio.file.Files.createTempDirectory("friend-reason").toFile()
            val memory=object:RuntimeCheckpoint {
                var data:JSONObject?=null
                override suspend fun load()=data
                override suspend fun save(checkpoint:JSONObject) {data=JSONObject(checkpoint.toString())}
            }
            try {
                val delta=mutableListOf<String>()
                val runtime=AgentRuntime(State(dir),checkpoint=memory,modelTransport=FriendModelTransport(client))
                assertEquals("visible",runtime.run("a","list",{},delta::add));assertEquals(listOf("visible"),delta)
                server.takeRequest();val messages=JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                assertEquals("private reasoning",messages.getJSONObject(messages.length()-2).getString("reasoning_content"))
                assertTrue(memory.data.toString().contains("private reasoning"))
                assertFalse(memory.data.toString().contains("fake-test-only"))
            } finally {dir.deleteRecursively()}
        }
    }
    @Test fun connectionTestDistinguishesTruncationFromAuthenticationFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(chunk("", "length") + "data: [DONE]\n\n"))
            val client=OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build()
            assertTrue(FriendModelTransport(client).testConnection("https://example.test/v1","mock","test-only",false).contains("认证连接成功"))
            val body=JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(256,body.getInt("max_tokens"));assertFalse(body.has("thinking"))
        }
    }

    @Test fun connectionTestUsesNormalizedEndpointAndSelectedTools() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val transport = FriendModelTransport(client)
            for (tools in listOf(false, true)) {
                server.enqueue(sse(chunk("OK", "stop") + "data: [DONE]\n\n"))
                assertTrue(transport.testConnection("https://example.test/v1/chat/completions", "mock", "test-only", tools).contains("成功"))
                val req=server.takeRequest();assertEquals("/v1/chat/completions",req.path)
                val body=JSONObject(req.body.readUtf8());assertEquals(tools,body.has("tools"));assertTrue(body.getBoolean("stream"))
                if(tools) assertEquals("connection_check",body.getJSONObject("tool_choice").getJSONObject("function").getString("name"))
                assertFalse(body.has("thinking"));assertNull(req.getHeader("api-key"))
            }
        }
    }
    @Test fun connectionTestOnlyClaimsToolsAfterActualNamedToolCall() = runBlocking {
        MockWebServer().use { server ->
            val client=OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build()
            val transport=FriendModelTransport(client)
            server.enqueue(sse(chunk("OK","stop")+"data: [DONE]\n\n"))
            assertTrue(transport.testConnection("https://example.test/v1","mock","test-only",true).contains("尚未验证"))
            server.enqueue(sse(toolEvent(callPart(0,"test","connection_check","{}"),finish="tool_calls")+"data: [DONE]\n\n"))
            assertEquals("模型连接成功，已验证工具调用能力",transport.testConnection("https://api.xiaomimimo.com/v1","mock","test-only",true))
            server.takeRequest();val req=server.takeRequest();val body=JSONObject(req.body.readUtf8())
            assertEquals("disabled",body.getJSONObject("thinking").getString("type"));assertNull(req.getHeader("api-key"))
        }
    }

    @Test fun finishReasonWithoutDoneMarkerIsAccepted() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(chunk("成都与重庆") + chunk(" GDP 对比", "stop")))
            val result = FriendModelTransport().complete(request(server)) {}
            assertEquals("成都与重庆 GDP 对比", result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        }
    }

    @Test fun contentTypesAreAdvisoryAndUsageOnlyChunksAreIgnored() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/plain")
                .setBody(chunk("第一段") + "data: " + JSONObject().put("choices", JSONArray()).put("usage", JSONObject().put("total_tokens", 10)) + "\n\n" + chunk("第二段", "stop")))
            assertEquals("第一段第二段", FriendModelTransport().complete(request(server)) {}
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(answer("JSON as text/plain").toString()))
            assertEquals("JSON as text/plain", FriendModelTransport().complete(request(server)) {}
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        }
    }

    @Test fun nonStandardContentArrayAndMissingFinishReasonAreNormalized() = runBlocking {
        MockWebServer().use { server ->
            val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject()
                .put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "成都"))
                    .put(JSONObject().put("type", "text").put("text", "重庆"))))))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()))
            val result = FriendModelTransport().complete(request(server)) {}
            assertEquals("成都重庆", result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
            assertEquals("stop", result.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        }
    }

    @Test fun serverIgnoringStreamCanReturnValidatedJson() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(answer("json answer").toString()))
            val delta=mutableListOf<String>()
            FriendModelTransport().complete(request(server),delta::add)
            assertEquals(listOf("json answer"),delta)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"choices\": []}"))
            try { FriendModelTransport().complete(request(server)) {};fail() } catch(e:FriendModelFailure) { assertEquals("PROTOCOL",e.code) }
        }
    }
    @Test fun disabledToolsCannotExecuteUnexpectedServerCall() = runBlocking {
        val dir=java.nio.file.Files.createTempDirectory("friend-no-tools").toFile()
        try {
            val state=State(dir);state.modelUseTools=false
            val runtime=AgentRuntime(state) { JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","tool_calls")
                .put("message",JSONObject().put("role","assistant").put("tool_calls",JSONArray().put(JSONObject().put("id","bad")
                    .put("function",JSONObject().put("name","write_file").put("arguments","{\"path\":\"bad.txt\",\"content\":\"bad\"}"))))))) }
            try {runtime.run("a","hi",{});fail()} catch(e:FriendModelFailure){assertEquals("PROTOCOL",e.code)}
            assertFalse(File(dir,"a/bad.txt").exists());assertEquals(listOf(Message("user","hi")),state.messages["a"]?.map { it.copy(id = "") })
        } finally {dir.deleteRecursively()}
    }

    private class State(override val workspaceRoot: File) : RuntimeState {
        override var experts = listOf(Expert("a","A","original"),Expert("b","B","second"))
        override val teams = listOf(Team("t","T",listOf("a","b")))
        override val skills = emptyList<Skill>(); override val knowledge = emptyList<Knowledge>()
        override var messages = emptyMap<String,List<Message>>()
        override var baseUrl = "https://example.test/v1/chat/completions"
        override var model = "mock"; override var apiKey = "fake-test-only"
        override var modelUseTools = true
        override var portablePackages = emptyMap<String,PortableStoredPackage>()
        override var portableSecrets = emptyMap<String,Map<String,String>>()
        override fun save() {}
    }
    private fun answer(value: String) = JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop")
        .put("message",JSONObject().put("role","assistant").put("content",value))))
    @Test fun disabledToolsAndFrozenConfigKeepConcurrentHistoryChanges() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("friend-model").toFile()
        try {
            val state = State(dir); state.modelUseTools = false
            val runtime = AgentRuntime(state) { request ->
                val buffer=okio.Buffer();request.body!!.writeTo(buffer); val payload=JSONObject(buffer.readUtf8())
                assertFalse(payload.has("tools")); assertEquals("mock",payload.getString("model")); assertTrue(payload.getBoolean("stream"))
                state.messages = state.messages + ("other" to listOf(Message("assistant","concurrent")))
                answer("ok")
            }
            val deltas=mutableListOf<String>()
            assertEquals("ok",runtime.run("a","hello",{},deltas::add)); assertEquals(listOf("ok"),deltas)
            assertEquals("concurrent",state.messages.getValue("other").single().content)
        } finally { dir.deleteRecursively() }
    }
    @Test fun teamPreflightChecksEveryMemberBeforeAppending() = runBlocking {
        val dir=java.nio.file.Files.createTempDirectory("friend-preflight").toFile()
        try {
            val state=State(dir); state.experts=state.experts.take(1)
            val runtime=AgentRuntime(state) { fail("Must not call model"); answer("no") }
            try { runtime.runTeam("t","hi",{});fail() } catch (e: FriendModelFailure) { assertEquals("CONFIG_EXPERT", e.code) }
            assertTrue(state.messages.isEmpty())
            state.apiKey=""
            try { runtime.run("a","hi",{});fail() } catch(e:FriendModelFailure) { assertEquals("CONFIG_MODEL",e.code) }
            assertTrue(state.messages.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun teamPreflightRejectsAnyMembersEngineProviderKeyOrUrlBeforeHistory()=runBlocking {
        val dir=java.nio.file.Files.createTempDirectory("friend-preflight-config").toFile()
        try {
            for (expected in listOf("ENGINE_UNSUPPORTED","CONFIG_PROVIDER","PROVIDER_UNSUPPORTED","CONFIG_MODEL","CONFIG_URL")) {
                val state=State(dir)
                val provider=JSONObject().put("ref","p").put("kind",if(expected=="PROVIDER_UNSUPPORTED") "anthropic" else "openai")
                    .put("base_url",if(expected=="CONFIG_URL") "http://example.test/v1" else "https://example.test/v1")
                val definition=JSONObject().put("ref","b-ref").put("engine",if(expected=="ENGINE_UNSUPPORTED") "desktop" else "native")
                    .put("model",JSONObject().put("provider_ref","p").put("model_id","mock"))
                val manifest=JSONObject().put("package_id","p").put("providers",JSONArray().also { if(expected!="CONFIG_PROVIDER")it.put(provider) })
                    .put("experts",JSONArray().put(definition))
                state.portablePackages=mapOf("p" to PortableStoredPackage(manifest.toString(),mapOf("b-ref" to "b"),"unused.xfpack",false))
                if(expected!="CONFIG_MODEL")state.portableSecrets=mapOf("p" to mapOf("p" to "test-only"))
                val runtime=AgentRuntime(state){fail("Must reject before model request");answer("bad")}
                try{runtime.preflight("t",true);fail(expected)}catch(e:FriendModelFailure){assertEquals(expected,e.code)}
                try{runtime.runTeam("t","hello",{});fail(expected)}catch(e:FriendModelFailure){assertEquals(expected,e.code)}
                assertTrue(state.messages.isEmpty())
            }
        }finally{dir.deleteRecursively()}
    }

    @Test fun teamSnapshotsModelAndPromptForAllMembers() = runBlocking {
        val dir=java.nio.file.Files.createTempDirectory("friend-snapshot").toFile()
        try {
            val state=State(dir);var count=0
            val runtime=AgentRuntime(state) { request ->
                val buffer=okio.Buffer();request.body!!.writeTo(buffer);val body=JSONObject(buffer.readUtf8())
                assertEquals("mock",body.getString("model"))
                if(count++==0) { state.model="changed"; state.experts=state.experts.map { it.copy(prompt="mutated") } }
                else assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("second"))
                answer("ok")
            }
            val deltas=mutableListOf<String>();runtime.runTeam("t","hi",{},deltas::add)
            assertEquals(2,count);assertTrue(deltas.any { it.contains("### A") });assertTrue(deltas.any { it.contains("### B") })
        } finally { dir.deleteRecursively() }
    }
    @Test fun realStreamToolRoundTripWritesAndReadsWorkspace() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(toolEvent(callPart(0,"call","write_file","{\"path\":\"proof.txt\",\"content\":\"actual\"}"),finish="tool_calls")+"data: [DONE]\n\n"))
            server.enqueue(sse(chunk("written", "stop")+"data: [DONE]\n\n"))
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val dir=java.nio.file.Files.createTempDirectory("friend-tools").toFile()
            try {
                val state=State(dir); val runtime=AgentRuntime(state,modelTransport=FriendModelTransport(client))
                assertEquals("written",runtime.run("a","write proof",{}))
                assertEquals("actual",File(dir,"a/proof.txt").readText())
                server.takeRequest();val second=JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                assertEquals("tool",second.getJSONObject(second.length()-1).getString("role"))
            } finally { dir.deleteRecursively() }
        }
    }
}
