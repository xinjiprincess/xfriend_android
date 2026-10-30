package com.singu.xfriend

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** Account-separated app-private storage. Never stores provider settings or credentials. */
internal class PhoneRepository private constructor(context: Context, val scopeKey: String, @Suppress("UNUSED_PARAMETER") keyed: Boolean) {
    constructor(context: Context, scope: String) : this(context, PhonePolicy.scopeKey(scope), true)
    private val base = checkedDirectory(File(context.filesDir, "phone"))
    private val directory = checkedDirectory(File(base, scopeKey))
    private val fileDirectory = checkedDirectory(File(directory, "files"))
    private val index = File(directory, "index.json")
    val lock: Any = locks.getOrPut(scopeKey) { Any() }

    fun read(): JSONObject = synchronized(lock) {
        checkedIndex()
        if (!index.exists() && !File(index.path + ".bak").exists()) return@synchronized JSONObject()
            .put("version", 1).put("items", JSONObject()).put("requests", JSONObject())
        val data = JSONObject(AtomicFile(index).readFully().toString(Charsets.UTF_8))
        require(data.optInt("version") == 1 && data.optJSONObject("items") != null && data.optJSONObject("requests") != null) {
            "本机事项数据格式无法读取，请勿覆盖原数据。"
        }
        data
    }
    fun write(data: JSONObject) = synchronized(lock) {
        checkedIndex()
        val atomic = AtomicFile(index)
        val output = atomic.startWrite()
        try { output.write(data.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    fun list(): List<JSONObject> = synchronized(lock) {
        val items = read().getJSONObject("items")
        items.keys().asSequence().mapNotNull { items.optJSONObject(it) }.toList()
            .sortedByDescending { it.optLong("created_at") }
    }
    fun publicItems(): JSONArray = JSONArray(list().map { publicItem(it) })
    fun fileFor(record: JSONObject): File {
        require(record.optString("kind") == "file") { "该事项不是文件。" }
        val stored = record.getString("stored_name")
        require(stored.matches(Regex("p-[a-f0-9]{32}--.+"))) { "文件标识无效。" }
        PhonePolicy.fileName(stored.substring(36))
        val file = File(fileDirectory, stored)
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == fileDirectory.canonicalFile) { "不允许符号链接或越界路径。" }
        return file
    }
    fun saveContent(record: JSONObject, bytes: ByteArray) {
        require(bytes.size <= PhonePolicy.MAX_FILE_BYTES) { "文件超过 1 MiB 上限。" }
        val file = fileFor(record)
        if (file.exists()) {
            require(PhonePolicy.sha256(file.readBytes()) == record.getString("sha256")) { "文件发生冲突，原文件未覆盖。" }
            return
        }
        Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
    }
    fun export(context: Context, id: String, destination: Uri) = synchronized(lock) {
        require(destination.scheme == "content") { "请选择系统文件选择器中的文件位置。" }
        val record = read().getJSONObject("items").optJSONObject(id) ?: error("文件不存在。")
        val file = fileFor(record)
        require(file.exists() && PhonePolicy.sha256(file.readBytes()) == record.getString("sha256")) { "文件校验失败，未导出。" }
        context.contentResolver.openOutputStream(destination, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } }
            ?: error("无法写入所选文件。")
    }
    private fun checkedIndex() {
        for (suffix in listOf("", ".bak", ".new")) require(!Files.isSymbolicLink(File(index.path + suffix).toPath())) {
            "不允许符号链接。"
        }
    }
    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private fun checkedDirectory(file: File): File {
            require(!Files.isSymbolicLink(file.toPath())) { "不允许符号链接。" }
            require((file.exists() || file.mkdirs()) && file.isDirectory) { "无法创建本机工作空间。" }
            return file
        }
        fun fromKey(context: Context, key: String): PhoneRepository {
            require(key.matches(Regex("[a-f0-9]{64}"))) { "账户标识无效。" }
            return PhoneRepository(context, key, true)
        }
        fun accountKeys(context: Context): List<String> {
            val base = File(context.filesDir, "phone")
            if (Files.isSymbolicLink(base.toPath())) return emptyList()
            return base.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && it.name.matches(Regex("[a-f0-9]{64}")) }.map { it.name }
        }
        fun publicItem(item: JSONObject): JSONObject {
            val out = JSONObject()
            for (key in listOf("id", "kind", "title", "details", "status", "created_at", "rule", "timezone", "local_time", "trigger_at", "next_at", "last_fired_at", "last_delivery", "due_date", "name", "mime", "bytes", "sha256", "file_ref")) {
                if (item.has(key)) out.put(key, if (key == "details") item.optString(key).take(600) else item.get(key))
            }
            if (item.optString("details").length > 600) out.put("details_truncated", true)
            return out
        }
    }
}
