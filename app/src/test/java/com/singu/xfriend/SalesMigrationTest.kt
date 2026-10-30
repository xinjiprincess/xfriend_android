package com.singu.xfriend

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SalesMigrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val password = "synthetic-migration-password"
    private fun manifest() = JSONObject().put("format", "xsalers-migration").put("version", 1).put("data", SalesData().json())
    private fun encoded(): ByteArray {
        val audio = temp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val out = ByteArrayOutputStream()
        SalesMigrationCodec.write(out, password, manifest(), mapOf("sample.wav" to audio), temp.root)
        return out.toByteArray()
    }

    @Test fun authenticatedArchivePreservesManifestAndMedia() {
        val read = SalesMigrationCodec.read(ByteArrayInputStream(encoded()), password, temp.newFolder())
        assertEquals("xsalers-migration", read.manifest.getString("format"))
        assertArrayEquals(byteArrayOf(1, 2, 3), read.files.getValue("sample.wav").readBytes())
    }

    @Test fun wrongPasswordOrTamperingDoesNotYieldAReadableArchive() {
        val data = encoded()
        assertThrows(IllegalArgumentException::class.java) { SalesMigrationCodec.read(ByteArrayInputStream(data), "different-password", temp.newFolder()) }
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { SalesMigrationCodec.read(ByteArrayInputStream(data), password, temp.newFolder()) }
    }

    @Test fun weakPasswordCannotProduceAnExport() {
        assertThrows(IllegalArgumentException::class.java) { SalesMigrationCodec.write(ByteArrayOutputStream(), "short", manifest(), emptyMap(), temp.root) }
    }

    private fun encryptedZip(name: String, write: (ZipOutputStream) -> Unit): ByteArray {
        val zip = temp.newFile()
        ZipOutputStream(zip.outputStream()).use { out -> out.putNextEntry(ZipEntry(name)); write(out); out.closeEntry() }
        return ByteArrayOutputStream().also { SalesMigrationCodec.encrypt(zip, it, password) }.toByteArray()
    }

    @Test fun zipTraversalIsRejectedWithoutWritingOutsideTheStage() {
        val data = encryptedZip("../escape.wav") { it.write(byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { SalesMigrationCodec.read(ByteArrayInputStream(data), password, temp.newFolder()) }
        assertFalse(java.io.File(temp.root, "escape.wav").exists())
    }

    @Test fun compressedMediaCannotExpandBeyondTheTotalLimit() {
        val data = encryptedZip("media/large.wav") { out -> repeat(32769) { out.write(ByteArray(1024)) } }
        assertThrows(IllegalArgumentException::class.java) { SalesMigrationCodec.read(ByteArrayInputStream(data), password, temp.newFolder()) }
    }

    @Test fun duplicatePhoneRemapsAllCrmReferencesAndRepeatedImportIsIdempotent() {
        val old = SalesData(customers = listOf(Customer("existing", "Current", "13800138000")))
        val imported = SalesData(
            customers = listOf(Customer("incoming", "Imported", "+86 13800138000")),
            records = listOf(Communication("record", "Call", "incoming")),
            opportunities = listOf(Opportunity("opp", "incoming", "Contract")),
            followUps = listOf(FollowUp("task", "incoming", "Follow up", recordId = "record")),
        )
        val merged = mergeMigrationData(old, imported)
        assertEquals(1, merged.customers.size)
        assertEquals("existing", merged.records.single().customerId)
        assertEquals("existing", merged.opportunities.single().customerId)
        assertEquals("existing", merged.followUps.single().customerId)
        assertEquals(merged, mergeMigrationData(merged, imported))
    }

    @Test fun localEditsArePreservedWhenImportingAnExistingRecord() {
        val record = Communication("record", "Current edit", text = "important edit")
        val merged = mergeMigrationData(SalesData(records = listOf(record)), SalesData(records = listOf(record.copy(text = "old export"))))
        assertEquals("important edit", merged.records.single().text)
    }
}
