package com.singu.xfriend

import org.json.JSONObject
import java.io.*
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class MigrationArchive(val manifest: JSONObject, val files: Map<String, File>)

internal object SalesMigrationCodec {
    const val MAX_BYTES = 32L * 1024 * 1024
    private val magic = "XSALESM1".toByteArray(Charsets.US_ASCII)
    private val mediaName = Regex("[a-zA-Z0-9-]+\\.(wav|mp3|jpg)")
    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        require(password.length in 10..256) { "迁移口令需为 10–256 位" }
        val spec = PBEKeySpec(password.toCharArray(), salt, 210000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    fun encrypt(archive: File, output: OutputStream, password: String) {
        require(archive.length() <= MAX_BYTES + 1024 * 1024) { "迁移文件过大，请分批整理录音后重试" }
        val random = SecureRandom(); val salt = ByteArray(16).also(random::nextBytes); val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)); updateAAD(magic + salt + iv) }
        output.write(magic); output.write(salt); output.write(iv)
        CipherOutputStream(output, cipher).use { encrypted -> archive.inputStream().use { it.copyTo(encrypted) } }
    }
    fun write(output: OutputStream, password: String, manifest: JSONObject, media: Map<String, File>, temp: File) {
        val bytes = manifest.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4 * 1024 * 1024) { "资料清单不能超过 4 MB" }
        require(bytes.size.toLong() + media.values.sumOf { it.length() } <= MAX_BYTES) { "单个迁移包最多 32 MB，请先整理录音资料" }
        val zip = File.createTempFile("migration-", ".zip", temp)
        try {
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                out.putNextEntry(ZipEntry("manifest.json")); out.write(bytes); out.closeEntry()
                media.forEach { (name, file) ->
                    require(mediaName.matches(name) && file.isFile) { "媒体文件缺失或名称无效" }
                    out.putNextEntry(ZipEntry("media/$name")); file.inputStream().use { it.copyTo(out) }; out.closeEntry()
                }
            }
            encrypt(zip, output, password)
        } finally { zip.delete() }
    }
    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        var total = 0L; val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) return total; total += n; require(total <= limit) { "迁移包超过大小限制" }; output.write(buffer, 0, n) }
    }
    fun read(input: InputStream, password: String, stage: File): MigrationArchive {
        require(stage.isDirectory)
        val header = ByteArray(36); DataInputStream(input).readFully(header)
        require(header.copyOfRange(0, 8).contentEquals(magic)) { "请选择 Xsalers 加密迁移包" }
        val salt = header.copyOfRange(8, 24); val iv = header.copyOfRange(24, 36)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)); updateAAD(header) }
        val zip = File(stage, "authenticated.zip")
        val encrypted = File(stage, "encrypted.bin")
        try {
            encrypted.outputStream().use { copyBounded(input, it, MAX_BYTES + 1024 * 1024 + 16) }
            // Authenticate the entire ciphertext before opening or extracting the ZIP.
            encrypted.inputStream().use { source -> CipherInputStream(source, cipher).use { decoded -> zip.outputStream().use { copyBounded(decoded, it, MAX_BYTES + 1024 * 1024) } } }
        } catch (failure: Exception) { zip.delete(); throw IllegalArgumentException("迁移口令错误、文件损坏或超过大小限制", failure) }
        finally { encrypted.delete() }
        var manifest: JSONObject? = null; var total = 0L; var entries = 0
        val files = linkedMapOf<String, File>(); val seen = mutableSetOf<String>()
        ZipInputStream(zip.inputStream().buffered()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                require(++entries <= 1001 && !entry.isDirectory && seen.add(entry.name)) { "迁移包含重复或过多条目" }
                when {
                    entry.name == "manifest.json" -> {
                        val bytes = archive.bounded(4 * 1024 * 1024); total += bytes.size
                        manifest = JSONObject(String(bytes, Charsets.UTF_8))
                    }
                    entry.name.startsWith("media/") && mediaName.matches(entry.name.removePrefix("media/")) -> {
                        val name = entry.name.removePrefix("media/")
                        val file = File(stage, "payload-${files.size}")
                        val written = file.outputStream().use { copyBounded(archive, it, MAX_BYTES - total) }; total += written
                        files[name] = file
                    }
                    else -> error("迁移包包含不允许的路径")
                }
                require(total <= MAX_BYTES) { "迁移包解压后超过 32 MB" }
                archive.closeEntry()
            }
        }
        zip.delete()
        val metadata = manifest ?: error("迁移包缺少资料清单")
        require(metadata.optString("format") == "xsalers-migration" && metadata.optInt("version") == 1) { "迁移格式或版本不支持" }
        return MigrationArchive(metadata, files)
    }
}

internal fun mergeMigrationData(old: SalesData, incoming: SalesData): SalesData {
    val customers = old.customers.toMutableList(); val mapping = mutableMapOf<String, String>()
    incoming.customers.forEach { value ->
        val phone = normalizedPhone(value.phone)
        val existing = customers.find { it.id == value.id || (phone.isNotBlank() && normalizedPhone(it.phone) == phone) }
        mapping[value.id] = existing?.id ?: value.id
        if (existing == null) customers.add(value)
    }
    fun customer(id: String): String = if (id.isBlank()) "" else mapping[id] ?: old.customers.find { it.id == id }?.id ?: error("迁移资料引用的客户缺失")
    val records = old.records + incoming.records.filterNot { r -> old.records.any { it.id == r.id } }.map { it.copy(customerId = customer(it.customerId)) }
    val opportunities = old.opportunities + incoming.opportunities.filterNot { r -> old.opportunities.any { it.id == r.id } }.map { it.copy(customerId = customer(it.customerId)) }
    val tasks = old.followUps + incoming.followUps.filterNot { r -> old.followUps.any { it.id == r.id } }.map {
        require(it.recordId.isBlank() || records.any { r -> r.id == it.recordId }) { "跟进引用的沟通记录缺失" }
        it.copy(customerId = customer(it.customerId))
    }
    val profiles = old.profiles + incoming.profiles.filterNot { p -> old.profiles.any { it.id == p.id } }
    return old.copy(customers = customers, records = records, opportunities = opportunities, followUps = tasks,
        profiles = profiles, active = old.active.ifBlank { incoming.active },
        expertVersions = (old.expertVersions + incoming.expertVersions).distinctBy { it.expertId to it.created })
}
