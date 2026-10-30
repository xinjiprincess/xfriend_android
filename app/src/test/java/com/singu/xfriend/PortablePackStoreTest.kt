package com.singu.xfriend

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PortablePackStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private class State(root: File) : PortableInstallState, RuntimeState {
        override val workspaceRoot = File(root, "workspaces")
        override val portableRoot = File(root, "portable")
        override var experts = listOf(Expert("keep", "Keep", "Unrelated"))
        override var teams = emptyList<Team>()
        override var skills = emptyList<Skill>()
        override val knowledge = emptyList<Knowledge>()
        override var messages = emptyMap<String, List<Message>>()
        override var portablePackages = emptyMap<String, PortableStoredPackage>()
        override var portableSecrets = emptyMap<String, Map<String, String>>()
        override val baseUrl = "https://global.invalid/v1"
        override val model = "global"
        override val apiKey = "never-send-global-key"
        var failNextSave = false
        override fun save() { if (failNextSave) { failNextSave = false; error("injected") } }
    }
    private fun pack(): PortablePack {
        val j = JSONObject("""{"package_id":"installed","client_version":"test","providers":[{"ref":"text:p","name":"Provider","kind":"openai","base_url":"https://portable.invalid/v1","has_secret":true,"extra":{"preserved":1}}],"experts":[{"ref":"expert:a","name":"A","system_prompt":"Full imported prompt","model":{"provider_ref":"text:p","model_id":"a/b"},"preferences":{"max_iters":2,"max_tokens":1234,"temperature":0.25,"top_p":0.8,"tools_disabled":["write_file"]},"files":{"SOUL.md":"assets/a/SOUL.md","skills/test/SKILL.md":"assets/a/SKILL.md","skills/test/run.py":"assets/a/run.py"}},{"ref":"expert:b","name":"B","model":{"provider_ref":"text:p","model_id":"b"}},{"ref":"expert:t","name":"Team","kind":"team","member_refs":["expert:a","expert:b"],"member_models":{"expert:a":{"provider_ref":"text:p","model_id":"team/override"}}}]}""")
        return PortablePackCodec.create(j, mapOf("assets/a/SOUL.md" to "Full soul".toByteArray(), "assets/a/SKILL.md" to "Trusted skill".toByteArray(), "assets/a/run.py" to "raise RuntimeError('not executed')".toByteArray()), mapOf("text:p" to "portable-key"))
    }

    @Test fun concreteInstallWritesModelsTeamsSkillsFilesAndPreservesMetadata() {
        val state = State(temp.newFolder()); var sales = SalesData()
        val plan = preparePortableInstall(pack(), includeSecrets = true)
        installPortablePack(state, plan) { transform -> transform(sales).also { sales = it } }
        assertEquals(3, state.experts.size); assertEquals(1, state.teams.size); assertEquals(1, state.skills.size)
        assertEquals(setOf("a/b", "b", "team/override"), sales.profiles.map { it.model }.toSet())
        assertTrue(sales.profiles.all { it.key == "portable-key" })
        val stored = state.portablePackages.getValue("installed")
        assertEquals(2, state.teams.single().members.size)
        assertTrue(state.experts.first { it.id == stored.resourceIds["expert:a"] }.skills.isEmpty())
        assertEquals("Full soul", File(state.workspaceRoot, "${stored.resourceIds["expert:a"]}/SOUL.md").readText())
        assertTrue(File(state.workspaceRoot, "${stored.resourceIds["expert:a"]}/skills/test/run.py").isFile)
        assertEquals("portable-key", state.portableSecrets["installed"]?.get("text:p"))
        assertEquals(stored, PortableStoredPackage.read(stored.json()))
        val archive = PortablePackCodec.decode(File(state.portableRoot, stored.archiveName).readBytes())
        assertTrue(archive.secrets().isEmpty())
        assertEquals(1, archive.manifest().getJSONArray("providers").getJSONObject(0).getJSONObject("extra").getInt("preserved"))
        installPortablePack(state, plan) { transform -> transform(sales).also { sales = it } }
        assertEquals(3, state.experts.size); assertEquals(3, sales.profiles.size)
        val firstId = stored.resourceIds.getValue("expert:a")
        state.experts = state.experts.map { if (it.id == firstId) it.copy(prompt = "Edited prompt") else it }
        File(state.workspaceRoot, "$firstId/SOUL.md").writeText("Edited file")
        val exported = PortablePackCodec.decode(exportInstalledPortablePack(state, "installed"))
        val expert = exported.manifest().getJSONArray("experts").getJSONObject(0)
        assertEquals("Edited prompt", expert.getString("system_prompt"))
        assertEquals("Edited file", portableUtf8(exported.files().getValue(expert.getJSONObject("files").getString("SOUL.md"))))
        assertTrue(exported.secrets().isEmpty())
    }

    @Test fun unselectedSecretsKeepExistingKeysAndFailedSaveRollsBackFilesAndAllCollections() {
        val state = State(temp.newFolder()); var sales = SalesData()
        val plan = preparePortableInstall(pack())
        val id = portableMobileResources(plan).profiles.first().id
        sales = sales.copy(profiles = listOf(ModelProfile(id, key = "existing")))
        installPortablePack(state, plan) { transform -> transform(sales).also { sales = it } }
        assertEquals("existing", sales.profiles.first { it.id == id }.key)
        assertTrue(state.portableSecrets.isEmpty())
        val beforeExperts = state.experts; val beforeSales = sales; val beforePacks = state.portablePackages
        val root = File(state.workspaceRoot, beforePacks.getValue("installed").resourceIds.getValue("expert:a"))
        File(root, "SOUL.md").writeText("User edit")
        state.failNextSave = true
        try { installPortablePack(state, preparePortableInstall(pack(), true, true)) { transform -> transform(sales).also { sales = it } }; fail("Expected write failure") }
        catch (_: IllegalStateException) { }
        assertEquals(beforeExperts, state.experts); assertEquals(beforeSales, sales); assertEquals(beforePacks, state.portablePackages)
        assertEquals("User edit", File(root, "SOUL.md").readText()); assertTrue(state.portableSecrets.isEmpty())
    }

    private fun body(request: Request): JSONObject { val buffer = Buffer(); request.body!!.writeTo(buffer); return JSONObject(buffer.readUtf8()) }
    private fun answer() = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", "Done"))))

    @Test fun installedRuntimeUsesProviderCredentialsPreferencesAndTeamMemberModel() = runBlocking {
        val state = State(temp.newFolder()); var sales = SalesData()
        installPortablePack(state, preparePortableInstall(pack(), true, true)) { transform -> transform(sales).also { sales = it } }
        val id = state.portablePackages.getValue("installed").resourceIds.getValue("expert:a")
        val models = mutableListOf<String>()
        val runtime = AgentRuntime(state) { request ->
            assertEquals("https://portable.invalid/v1/chat/completions", request.url.toString())
            assertEquals("Bearer portable-key", request.header("Authorization"))
            val b = body(request); models.add(b.getString("model"))
            if (b.getString("model") != "b") {
                assertEquals(1234, b.getInt("max_tokens")); assertEquals(0.25, b.getDouble("temperature"), 0.0)
                assertFalse(b.getJSONArray("tools").objects().any { it.getJSONObject("function").getString("name") == "write_file" })
                assertTrue(b.getJSONArray("messages").getJSONObject(0).getString("content").contains("Trusted skill"))
            }
            answer()
        }
        runtime.run(id, "Review", {})
        runtime.runTeam(state.teams.single().id, "Review", {})
        assertEquals(listOf("a/b", "team/override", "b"), models)
        val resolver = portableSalesModelResolver { sales.profiles.map { it.copy(key = "new-ui-key") } }
        assertEquals("new-ui-key", resolver.resolve("installed", "text:p", "a/b")!!.apiKey)
    }

    @Test fun syncPreservesXfpackAndSingleMemberTeamsButPackExportRejectsThem() {
        val state = State(temp.newFolder()); var sales = SalesData()
        installPortablePack(state, preparePortableInstall(pack(), true, true)) { transform -> transform(sales).also { sales = it } }
        val metadata = portableSyncMetadata(state)
        assertEquals(6, metadata.size) // Two experts, team, three model profiles.
        assertFalse(metadata.values.joinToString().contains("portable-key"))
        val rows = mutableListOf<JSONObject>()
        state.experts.filterNot { it.id == "keep" }.forEach { expert ->
            val p = JSONObject().put("name", expert.name).put("prompt", expert.prompt).put("skills", JSONArray())
                .put("_xfpack", metadata.getValue("expert:${expert.id}").getJSONObject("_xfpack"))
            rows.add(JSONObject().put("entity_type", "expert").put("entity_id", expert.id).put("payload", p))
        }
        state.teams.forEach { team -> rows.add(JSONObject().put("entity_type", "team").put("entity_id", team.id)
            .put("payload", JSONObject().put("name", team.name).put("members", JSONArray(team.members)).put("_xfpack", metadata.getValue("team:${team.id}").getJSONObject("_xfpack")))) }
        val restored = portablePackFromSync(rows, "sync-fixture")
        assertEquals("team/override", restored.manifest().getJSONArray("experts").getJSONObject(2).getJSONObject("member_models").getJSONObject("expert:a").getString("model_id"))
        assertEquals(3, restored.files().size); assertTrue(restored.secrets().isEmpty())
        state.teams = state.teams.map { it.copy(members = it.members.take(1)) }
        assertTrue(portableSyncMetadata(state).containsKey("team:${state.teams.single().id}"))
        try { exportInstalledPortablePack(state, "installed"); fail("Cannot export a one-member team") } catch (_: InvalidPortablePack) { }
        rows.last().getJSONObject("payload").put("members", JSONArray(state.teams.single().members))
        try { portablePackFromSync(rows); fail("Cannot import a one-member xfpack team") } catch (_: InvalidPortablePack) { }
    }
}
