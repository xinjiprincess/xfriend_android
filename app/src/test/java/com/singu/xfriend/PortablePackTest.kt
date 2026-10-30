package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortablePackTest {
    private fun manifest() = JSONObject("""{"format":"xfriend-portable","format_version":2,"package_id":"fixture","client_version":"test","providers":[{"ref":"text:p","name":"模型","kind":"openai","base_url":"https://example.invalid/v1","models":[{"id":"a/b","enabled":true}],"extra":{"custom":{"keep":true}}}],"experts":[{"ref":"expert:a","name":"专家","model":{"provider_ref":"text:p","model_id":"a/b"},"preferences":{"max_iters":9,"temperature":0.2,"tools_disabled":["shell"]},"files":{"SOUL.md":"assets/a/SOUL.md","skills/check/run.py":"assets/a/run.py"},"dependencies":["shell"]},{"ref":"expert:b","name":"B","engine":"dsh"},{"ref":"expert:t","name":"Team","kind":"team","member_refs":["expert:a","expert:b"],"member_models":{"expert:a":{"provider_ref":"text:p","model_id":"override/model"}}}]}""")
    private fun assets() = mapOf("assets/a/SOUL.md" to "你好".toByteArray(), "assets/a/run.py" to "raise RuntimeError('never execute')".toByteArray())
    private fun archive(manifest: String, files: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            (mapOf("manifest.json" to manifest.toByteArray()) + files).forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        }
        return out.toByteArray()
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("Accepted invalid pack") } catch (e: InvalidPortablePack) { assertNull(e.cause); assertFalse(e.message!!.contains("credential")) } }

    @Test fun roundtripPreservesEveryMetadataFieldAndAssetWithoutExecutingScripts() {
        val pack = PortablePackCodec.create(manifest(), assets())
        val restored = PortablePackCodec.decode(PortablePackCodec.encode(pack))
        assertTrue(pack.manifest().similar(restored.manifest()))
        assets().forEach { (path, bytes) -> assertArrayEquals(bytes, restored.files()[path]) }
        val exposed = restored.files(); exposed.values.first().fill(0)
        assertArrayEquals(assets().values.first(), restored.files().values.first())
        restored.manifest().put("package_id", "mutated")
        assertEquals("fixture", restored.manifest().getString("package_id"))
        val plan = preparePortableInstall(restored)
        assertFalse(plan.trustSkills); assertTrue(plan.secrets().isEmpty())
        assertTrue(plan.notices.any { it.capability == "engine:dsh" })
        assertTrue(plan.notices.any { it.capability == "dependency:shell" })
    }

    @Test fun encryptionChecksPasswordHeaderTagAndSecretConsent() {
        val j = manifest(); j.getJSONArray("providers").getJSONObject(0).put("has_secret", true)
        val pack = PortablePackCodec.create(j, assets(), mapOf("text:p" to "credential"))
        rejected { PortablePackCodec.encode(pack) }
        val encoded = PortablePackCodec.encode(pack, "密码")
        assertTrue(PortablePackCodec.isEncrypted(encoded))
        assertFalse(String(encoded).contains("credential"))
        assertEquals("credential", PortablePackCodec.decode(encoded, "密码").secrets()["text:p"])
        assertTrue(preparePortableInstall(pack).secrets().isEmpty())
        assertEquals("credential", preparePortableInstall(pack, includeSecrets = true).secrets()["text:p"])
        rejected { PortablePackCodec.decode(encoded, "wrong") }
        val tampered = encoded.copyOf(); tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        rejected { PortablePackCodec.decode(tampered, "密码") }
        val header = encoded.copyOf(); header[15] = 'x'.code.toByte()
        rejected { PortablePackCodec.decode(header, "密码") }
    }

    @Test fun oldVersionReadsButExportUpgradesToV2() {
        val j = manifest().put("format_version", 1)
        j.getJSONArray("experts").getJSONObject(2).remove("member_models")
        val hashes = JSONObject(); assets().forEach { (k, v) -> hashes.put(k, PortablePackCodec.sha256(v)) }
        j.put("checksums", hashes)
        val pack = PortablePackCodec.decode(archive(j.toString(), assets()))
        assertEquals(1, pack.manifest().getInt("format_version"))
        assertEquals(2, PortablePackCodec.decode(PortablePackCodec.encode(pack)).manifest().getInt("format_version"))
        rejected { PortablePackCodec.create(manifest().put("format_version", 1), assets()) }
    }

    @Test fun duplicateUnknownFieldsBadReferencesAndUntrackedFilesFail() {
        rejected { PortablePackCodec.decode(archive("""{"package_id":"x","package_id":"y","client_version":"x"}""")) }
        rejected { PortablePackCodec.decode(archive("""{"package_id":"x","client_version":"x","providers":[{"ref":"text:p","name":"p","kind":"x","extra":{"same":1,"same":2}}]}""")) }
        rejected { PortablePackCodec.create(manifest().put("unsupported", true), assets()) }
        rejected { PortablePackCodec.create(manifest().also { it.getJSONArray("experts").getJSONObject(2).put("member_refs", JSONArray(listOf("expert:a", "missing"))) }, assets()) }
        rejected { PortablePackCodec.create(manifest(), assets() + ("untracked" to byteArrayOf(1))) }
        rejected { PortablePackCodec.create(manifest().also { it.getJSONArray("providers").getJSONObject(0).put("base_url", "https://user:credential@example.com") }, assets()) }
    }

    @Test fun unsafePathsUnicodeCollisionsAndHashesFail() {
        listOf("../x", "/x", "C:x", "x\\y", "x//y", "x/CON.txt", "x/COM0", "x/a.", "x/a ", "x\u0000", "a".repeat(241)).forEach {
            try { PortablePackCodec.safePath(it); fail("Accepted path") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(portableCaseFold("Straße"), portableCaseFold("STRASSE"))
        assertEquals(portableCaseFold("Σςσ"), "σσσ")
        listOf("assets/A" to "assets/a", "assets/é" to "assets/e\u0301", "assets/straße" to "assets/STRASSE").forEach { (a, b) ->
            rejected { PortablePackCodec.decode(archive("{}", mapOf(a to byteArrayOf(), b to byteArrayOf()))) }
        }
        val j = manifest().put("checksums", JSONObject().also { h -> assets().forEach { (p, b) -> h.put(p, PortablePackCodec.sha256(b)) } })
        rejected { PortablePackCodec.decode(archive(j.toString(), assets() + ("assets/a/SOUL.md" to "changed".toByteArray()))) }
    }

    @Test fun desktopLimitsAndSymlinkMetadataAreEnforced() {
        assertEquals(8 * 1024 * 1024, PortablePackCodec.MAX_FILE)
        assertEquals(64 * 1024 * 1024, PortablePackCodec.MAX_TOTAL)
        assertEquals(72 * 1024 * 1024, PortablePackCodec.MAX_ARCHIVE)
        assertEquals(4000, PortablePackCodec.MAX_FILES)
        rejected { PortablePackCodec.create(manifest(), assets() + ("assets/a/SOUL.md" to ByteArray(PortablePackCodec.MAX_FILE + 1))) }
        val zip = archive("""{"package_id":"x","client_version":"x"}""")
        val central = (0..zip.size - 4).first { zip[it] == 0x50.toByte() && zip[it + 1] == 0x4b.toByte() && zip[it + 2] == 1.toByte() && zip[it + 3] == 2.toByte() }
        zip[central + 41] = 0xa0.toByte()
        rejected { PortablePackCodec.decode(zip) }
        // A valid hash does not legalize an unencrypted secret file.
        val secrets = "{}".toByteArray()
        rejected { PortablePackCodec.decode(archive("""{"package_id":"x","client_version":"x","checksums":{"secrets.json":"${PortablePackCodec.sha256(secrets)}"}}""", mapOf("secrets.json" to secrets))) }
    }

    @Test fun legacyJsonRemainsAnExplicitSeparateBranch() {
        val legacy = """{"format":"xfriend-mobile-export","version":1,"experts":[],"teams":[],"skills":[],"knowledge":[],"providers":[]}""".toByteArray()
        assertTrue(parseDesktopImport(legacy) is DesktopImportDocument.Legacy)
        assertTrue(parseDesktopImport(PortablePackCodec.encode(PortablePackCodec.create(manifest(), assets()))) is DesktopImportDocument.Native)
    }

    @Test fun desktopGeneratedFixturesAndReciprocalExports() {
        val root = File("app/src/test/resources/portable-pack")
        for (version in listOf(1, 2)) {
            for (encrypted in listOf(false, true)) {
                val name = "desktop-v$version${if (encrypted) "-encrypted" else ""}.xfpack"
                val password = if (encrypted) "fixture-password" else ""
                val bytes = if (root.exists()) File(root, name).readBytes() else javaClass.getResourceAsStream("/portable-pack/$name")!!.use { it.readBytes() }
                val pack = PortablePackCodec.decode(bytes, password)
                assertEquals(version, pack.manifest().getInt("format_version"))
                assertEquals("你好 desktop", portableUtf8(pack.files().getValue("assets/a/SOUL.md")))
                val output = PortablePackCodec.encode(pack, password)
                assertEquals(2, PortablePackCodec.decode(output, password).manifest().getInt("format_version"))
                System.getProperty("portable.interop.output")?.let { File(it, "mobile-$name").writeBytes(output) }
            }
        }
    }
}
