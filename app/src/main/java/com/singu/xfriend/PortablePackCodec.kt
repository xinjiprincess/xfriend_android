package com.singu.xfriend

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** In-memory only: never extracts files, fetches dependencies or executes packaged scripts. */
object PortablePackCodec {
    const val MAX_FILE = 8 * 1024 * 1024
    const val MAX_TOTAL = 64 * 1024 * 1024
    const val MAX_ARCHIVE = 72 * 1024 * 1024
    const val MAX_FILES = 4000
    private val magic = "XFPACK-ENC1\n".toByteArray(Charsets.US_ASCII)

    fun isEncrypted(bytes: ByteArray): Boolean = bytes.size >= magic.size && bytes.copyOfRange(0, magic.size).contentEquals(magic)

    fun decode(bytes: ByteArray, password: String = ""): PortablePack = guarded {
        require(bytes.size <= MAX_ARCHIVE)
        val encrypted = isEncrypted(bytes)
        val files = unzip(if (encrypted) decrypt(bytes, password) else bytes)
        val manifest = portableJson(files.remove("manifest.json") ?: throw InvalidPortablePack())
        validatePortableManifest(manifest)
        val checksums = manifest.packObject("checksums")
        require(files.keys == checksums.packKeys())
        files.forEach { (path, data) -> require(sha256(data) == checksums.packString(path)) }
        val secretBytes = files.remove("secrets.json")
        require(secretBytes == null || encrypted)
        val secretJson = if (secretBytes == null || secretBytes.isEmpty()) JSONObject() else portableJson(secretBytes)
        val secrets = secretJson.packKeys().associateWith { secretJson.packString(it).also { s -> require(s.codePointCount(0, s.length) <= 100000) } }
        val providers = manifest.packArray("providers")
        val secretRefs = (0 until providers.length()).map { providers.getJSONObject(it) }.filter { it.optBoolean("has_secret", false) }.map { it.getString("ref") }.toSet()
        require(secretRefs == secrets.keys)
        val used = mutableSetOf<String>()
        val experts = manifest.packArray("experts")
        repeat(experts.length()) { index ->
            val e = experts.getJSONObject(index)
            val mappings = e.packObject("files")
            mappings.packKeys().forEach { relative ->
                safePath(relative)
                require(relative in setOf("SOUL.md", "IDENTITY.md", "AGENTS.md", "TOOLS.md", "HEARTBEAT.md") || (relative.startsWith("skills/") && relative.split('/').size >= 3))
                val path = mappings.packString(relative)
                require(path in files && path.startsWith("assets/")); used.add(path)
            }
            if (e.has("avatar_file") && !e.isNull("avatar_file") && e.getString("avatar_file").isNotEmpty()) {
                val path = e.getString("avatar_file"); require(path in files); used.add(path)
            }
        }
        require(used == files.keys)
        PortablePack(manifest, files, secrets)
    }

    /** Build a document using the same validation as decode; export always upgrades to v2. */
    fun create(manifest: JSONObject, files: Map<String, ByteArray> = emptyMap(), secrets: Map<String, String> = emptyMap()): PortablePack = guarded {
        validatePortableManifest(manifest)
        // Encode/decode is also the single source of truth for asset references and secret policy.
        val password = if (secrets.isEmpty()) "" else random(32).hex()
        decode(encodeUnchecked(PortablePack(manifest, files, secrets), password), password)
    }

    fun encode(pack: PortablePack, password: String = ""): ByteArray = guarded {
        val bytes = encodeUnchecked(pack, password)
        decode(bytes, password)
        bytes
    }

    private fun encodeUnchecked(pack: PortablePack, password: String): ByteArray {
        val files = pack.files().toMutableMap()
        require("manifest.json" !in files && "secrets.json" !in files)
        val secrets = pack.secrets()
        if (secrets.isNotEmpty()) {
            require(password.isNotEmpty())
            files["secrets.json"] = JSONObject(secrets).toString().toByteArray(Charsets.UTF_8)
        }
        require(files.size < MAX_FILES && files.values.all { it.size <= MAX_FILE })
        require(files.values.sumOf { it.size.toLong() } <= MAX_TOTAL - MAX_FILE)
        val hashes = JSONObject()
        files.forEach { (path, data) -> hashes.put(safePath(path), sha256(data)) }
        val manifest = pack.manifest().put("format_version", 2).put("checksums", hashes)
        validatePortableManifest(manifest)
        val metadata = manifest.toString().toByteArray(Charsets.UTF_8)
        require(metadata.size <= MAX_FILE)
        val output = ByteArrayOutputStream()
        // STORED avoids emitting a desktop-rejected ratio for highly compressible valid assets.
        ZipOutputStream(output).use { zip ->
            fun entry(path: String, data: ByteArray) {
                val e = ZipEntry(path).apply { method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size; crc = CRC32().apply { update(data) }.value }
                zip.putNextEntry(e); zip.write(data); zip.closeEntry()
            }
            entry("manifest.json", metadata)
            files.forEach { (path, data) -> entry(path, data) }
        }
        return (if (password.isEmpty()) output.toByteArray() else encrypt(output.toByteArray(), password)).also { require(it.size <= MAX_ARCHIVE) }
    }

    fun safePath(value: String): String {
        require(value.isNotEmpty() && value.codePointCount(0, value.length) <= 240 && '\\' !in value && '\u0000' !in value)
        value.split('/').forEach { part ->
            require(part.isNotEmpty() && part != "." && part != ".." && !part.endsWith(' ') && !part.endsWith('.'))
            require(part.none { it.code < 32 || it in ":<>\"|?*" })
            require(!Regex("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\\..*)?").matches(part))
        }
        return value
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unhex(value: String): ByteArray {
        val compact = value.filterNot { it in " \t\r\n\u000b\u000c" }
        require(compact.length % 2 == 0 && compact.all { it in "0123456789abcdefABCDEF" })
        return compact.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun random(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
    private fun key(password: String, salt: ByteArray): ByteArray {
        require(password.isNotEmpty())
        val input = password.toByteArray(Charsets.UTF_8)
        return try {
            ByteArray(32).also { result ->
                Argon2BytesGenerator().apply { init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt).withMemoryAsKB(65536).withIterations(3).withParallelism(1).build()) }.generateBytes(input, result)
            }
        } finally { input.fill(0) }
    }
    private fun crypt(mode: Int, data: ByteArray, aad: ByteArray, password: String, salt: ByteArray, nonce: ByteArray): ByteArray {
        val key = key(password, salt)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad) }.doFinal(data)
        } finally { key.fill(0) }
    }
    private fun encrypt(data: ByteArray, password: String): ByteArray {
        val salt = random(16); val nonce = random(12)
        val header = JSONObject().put("kdf", "argon2id").put("memory_kib", 65536).put("iterations", 3).put("parallelism", 1).put("salt", salt.hex()).put("nonce", nonce.hex()).toString().toByteArray(Charsets.UTF_8)
        val aad = magic + java.nio.ByteBuffer.allocate(4).putInt(header.size).array() + header
        return aad + crypt(Cipher.ENCRYPT_MODE, data, aad, password, salt, nonce)
    }
    private fun decrypt(data: ByteArray, password: String): ByteArray {
        require(data.size >= magic.size + 4)
        val length = java.nio.ByteBuffer.wrap(data, magic.size, 4).int
        require(length in 1..512 && data.size >= magic.size + 4 + length + 16)
        val end = magic.size + 4 + length
        val header = portableJson(data.copyOfRange(magic.size + 4, end))
        require(header.packKeys() == setOf("kdf", "memory_kib", "iterations", "parallelism", "salt", "nonce"))
        require(header.packString("kdf") == "argon2id")
        mapOf("memory_kib" to 65536, "iterations" to 3, "parallelism" to 1).forEach { (k, v) -> require(header.get(k) is Number && (header.get(k) as Number).toDouble() == v.toDouble()) }
        val salt = unhex(header.packString("salt")); val nonce = unhex(header.packString("nonce"))
        require(salt.size == 16 && nonce.size == 12)
        return crypt(Cipher.DECRYPT_MODE, data.copyOfRange(end, data.size), data.copyOfRange(0, end), password, salt, nonce)
    }

    /** Read central metadata ourselves: ZipInputStream hides Unix symlinks and declared sizes. */
    private fun unzip(data: ByteArray): MutableMap<String, ByteArray> {
        fun u16(p: Int): Int { require(p >= 0 && p.toLong() + 2 <= data.size); return (data[p].toInt() and 255) or ((data[p + 1].toInt() and 255) shl 8) }
        fun u32(p: Int): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
        fun slice(p: Int, n: Int): ByteArray { require(p >= 0 && n >= 0 && p.toLong() + n <= data.size); return data.copyOfRange(p, p + n) }
        val eocd = (data.size - 22 downTo maxOf(0, data.size - 65557)).firstOrNull { u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == data.size } ?: throw InvalidPortablePack()
        require(u16(eocd + 4) == 0 && u16(eocd + 6) == 0 && u16(eocd + 8) == u16(eocd + 10))
        val count = u16(eocd + 10); require(count <= MAX_FILES)
        val centralSize = u32(eocd + 12); val centralStart = u32(eocd + 16)
        require(centralStart + centralSize == eocd.toLong())
        var pos = centralStart.toInt(); var total = 0L
        val names = mutableSetOf<String>(); val files = linkedMapOf<String, ByteArray>()
        val ranges = mutableListOf<LongRange>()
        repeat(count) {
            require(u32(pos) == 0x02014b50L)
            val flags = u16(pos + 8); val method = u16(pos + 10)
            val crc = u32(pos + 16); val compressed = u32(pos + 20); val size = u32(pos + 24)
            val nameSize = u16(pos + 28); val extraSize = u16(pos + 30); val commentSize = u16(pos + 32)
            val mode = (u32(pos + 38) shr 16).toInt(); val offset = u32(pos + 42)
            require(u16(pos + 34) == 0 && offset <= Int.MAX_VALUE)
            val nameBytes = slice(pos + 46, nameSize)
            val name = safePath(if (flags and 0x800 != 0) portableUtf8(nameBytes) else String(nameBytes, charset("Cp437")))
            require(names.add(portableCaseFold(Normalizer.normalize(name, Normalizer.Form.NFC))))
            require(mode and 0xf000 != 0xa000 && flags and 1 == 0 && method in listOf(0, 8))
            require(size <= MAX_FILE && size <= maxOf(1024 * 1024L, compressed * 500))
            total += size; require(total <= MAX_TOTAL)
            val local = offset.toInt()
            require(u32(local) == 0x04034b50L && u16(local + 8) == method && u16(local + 6) == flags)
            val localNameSize = u16(local + 26); val localExtraSize = u16(local + 28)
            require(slice(local + 30, localNameSize).contentEquals(nameBytes))
            val start = local.toLong() + 30 + localNameSize + localExtraSize
            require(start + compressed <= centralStart)
            val range = offset until start + compressed
            require(ranges.none { range.first <= it.last && it.first <= range.last }); ranges.add(range)
            val source = slice(start.toInt(), compressed.toInt())
            val blob = if (method == 0) { require(compressed == size); source } else {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(source)
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buffer)
                        require(n > 0 || inflater.finished())
                        require(out.size().toLong() + n <= size && out.size() + n <= MAX_FILE)
                        out.write(buffer, 0, n)
                    }
                    require(inflater.bytesRead == compressed); out.toByteArray()
                } finally { inflater.end() }
            }
            require(blob.size.toLong() == size && CRC32().apply { update(blob) }.value == crc)
            files[name] = blob
            pos += 46 + nameSize + extraSize + commentSize
            require(pos <= eocd)
        }
        require(pos == eocd)
        return files
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (_: Exception) { throw InvalidPortablePack() }
}
