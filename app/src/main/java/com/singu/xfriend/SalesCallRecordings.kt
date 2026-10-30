package com.singu.xfriend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 电话录音接管。
 *
 * Android 10 起第三方应用无法在通话中录音；这里接管的是系统/录音机应用已经保存的通话录音文件，
 * 复制到应用私有目录后复用现有的语音转写 → 复盘分析流程。文字保存成功后，用户可以删除源音频，
 * 只保留文字与复盘报告。
 */
internal data class CallRecording(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val capturedAt: Long?,
    val folder: String = "",
    val customerId: String = "",
    val localFile: File? = null,
)

internal data class CallRecordingSettings(val autoScan: Boolean = true, val deleteAfterTranscribe: Boolean = true)

internal object CallRecordings {
    const val MAX_RESULTS = 200

    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    /** 应用内「现场沟通录音」目录，接管后可与其他来源一起转写。 */
    fun appRecordingsDir(context: Context): File = File(context.getExternalFilesDir(null), "recordings")

    private fun appRecordingDirs(context: Context): List<File> =
        context.getExternalFilesDirs(null).filterNotNull().map { File(it, "recordings") }

    /** 删除应用录音目录中的源文件（适用于本地录音来源；系统媒体库走 MediaStore.createDeleteRequest）。 */
    fun deleteLocalSource(context: Context, name: String): Boolean {
        if (name.isBlank()) return false
        return appRecordingDirs(context).flatMap { dir -> dir.listFiles().orEmpty().filter { it.name == name } }
            .any { runCatching { it.delete() }.getOrDefault(false) }
    }

    private val keyword = Regex("(电话|通话|录音|录制|呼入|呼出|来电|去电|拨打|接听|call|record|voice)", RegexOption.IGNORE_CASE)
    private val audioExt = Regex("\\.(wav|mp3|m4a|aac|amr|ogg|opus|3gp|flac)$", RegexOption.IGNORE_CASE)
    private val phoneLike = Regex("(?:\\+?86[\\s-]?)?1[3-9]\\d{9}|0\\d{2,3}[\\s-]?\\d{7,8}")
    private val dateToken = Regex("(?<!\\d)(?:\\d{4}[-_.年]?\\d{1,2}[-_.月]?\\d{1,2}日?[\\s_-]*\\d{1,2}[:：._-]?\\d{2}(?:[:：._-]?\\d{2})?|\\d{1,2}[月.-]\\d{1,2}日?[\\s_-]+\\d{1,2}[:：._-]\\d{2}(?:[:：._-]\\d{2})?)(?!\\d)")

    fun parsePhone(name: String): String = phoneLike.find(name)?.value?.let(::normalizedPhone).orEmpty()

    fun baseName(name: String): String = name.substringBeforeLast('.').substringBefore(" (").trim()

    private val genericTitle = Regex("^(?:call|record|recorder|recording|voice|audio|sound|mic|通话|录音|电话|录音文件|通话录音)$", RegexOption.IGNORE_CASE)

    fun titleOf(name: String): String {
        val stripped = baseName(name).replace(dateToken, "").replace(phoneLike, "")
            .replace(Regex("[_\\s]+"), " ").trim(' ', '-', '·', '_')
        return if (stripped.isBlank() || genericTitle.matches(stripped)) "通话录音" else stripped.take(80)
    }

    /** 应用内录音是 PCM WAV：直接从文件头计算时长，避免列表里显示「时长未知」。 */
    fun wavDurationMs(file: File): Long? = runCatching {
        if (file.length() <= 44) return null
        java.io.RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            if (raf.read(header) != 44) return null
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            fun leInt(offset: Int) = (header[offset].toInt() and 0xFF) or ((header[offset + 1].toInt() and 0xFF) shl 8) or
                ((header[offset + 2].toInt() and 0xFF) shl 16) or ((header[offset + 3].toInt() and 0xFF) shl 24)
            val byteRate = leInt(28)
            if (byteRate <= 0) return null
            (file.length() - 44) * 1000 / byteRate
        }
    }.getOrNull()

    /** 从常见录音文件名里解析录制时间：「20261016_1432」「10月16日 14:32」「2026-10-16 14-32-05」等。 */
    fun parseCapturedAt(name: String, fallback: Long?): Long? {
        val text = baseName(name)
        val zone = ZoneId.systemDefault()
        fun stamp(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long? = runCatching {
            LocalDateTime.of(year, month, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
        Regex("(?<!\\d)(\\d{4})[-_.年]?(\\d{1,2})[-_.月]?(\\d{1,2})日?[\\s_-]*(\\d{1,2})[:：._-]?(\\d{2})(?:[:：._-]?(\\d{2}))?(?!\\d)")
            .find(text)?.let { captured ->
                val (year, month, day) = captured.destructured
                val hour = captured.groupValues[4].toInt()
                val minute = captured.groupValues[5].toInt()
                val second = captured.groupValues[6].toIntOrNull() ?: 0
                val time = stamp(year.toInt(), month.toInt(), day.toInt(), hour, minute, second)
                if (time != null && year.toInt() in 2000..2100) return time
            }
        Regex("(?<!\\d)(\\d{1,2})[月.-](\\d{1,2})日?[\\s_-]+(\\d{1,2})[:：._-](\\d{2})(?:[:：._-](\\d{2}))?")
            .find(text)?.let { monthDay ->
                val month = monthDay.groupValues[1].toInt()
                val day = monthDay.groupValues[2].toInt()
                val hour = monthDay.groupValues[3].toInt()
                val minute = monthDay.groupValues[4].toInt()
                val second = monthDay.groupValues[5].toIntOrNull() ?: 0
                var year = LocalDate.now().year
                var time = stamp(year, month, day, hour, minute, second)
                if (time != null && time > System.currentTimeMillis() + 24 * 3600_000L) {
                    year -= 1
                    time = stamp(year, month, day, hour, minute, second)
                }
                if (time != null) return time
            }
        return fallback?.takeIf { it > 0 }
    }

    fun dateText(ts: Long?): String {
        if (ts == null) return "时间未知"
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(ts).atZone(zone)
        val today = LocalDate.now(zone); val day = time.toLocalDate()
        val clock = "%02d:%02d".format(time.hour, time.minute)
        return when (day) {
            today -> "今天 $clock"
            today.minusDays(1) -> "昨天 $clock"
            else -> if (day.year == today.year) "%d月%d日 %s".format(day.monthValue, day.dayOfMonth, clock)
            else "%d年%d月%d日 %s".format(day.year, day.monthValue, day.dayOfMonth, clock)
        }
    }

    fun durationText(durationMs: Long?): String {
        if (durationMs == null || durationMs < 1000) return "时长未知"
        val seconds = durationMs / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%d KB".format(maxOf(1, bytes / 1024))
    }

    private fun looksLikeCall(displayName: String, folder: String, durationMs: Long?): Boolean {
        if (!audioExt.containsMatchIn(displayName)) return false
        if (durationMs != null && durationMs > MAX_AUDIO_SECONDS * 1000L) return false
        val name = baseName(displayName)
        val keywordHit = keyword.containsMatchIn(name) || keyword.containsMatchIn(folder)
        val phoneHit = parsePhone(name).isNotEmpty()
        val timeHit = parseCapturedAt(name, null) != null
        val folderHit = folder.contains("Record", ignoreCase = true) || folder.contains("Call", ignoreCase = true) || folder.contains("录音") || folder.contains("通话")
        return keywordHit || phoneHit || timeHit || folderHit
    }

    /** 读取系统音频库与应用录音目录，返回尚未被接管的通话录音候选（已自动匹配客户）。 */
    suspend fun scan(context: Context, data: SalesData): List<CallRecording> = withContext(Dispatchers.IO) {
        val permitted = hasPermission(context)
        android.util.Log.d("CallRecordings", "scan start permission=$permitted")
        val takenUris = data.records.map { it.sourceUri }.filter { it.isNotBlank() }.toSet()
        val takenFiles = data.records.filter { it.kind == "audio" }
            .flatMap { listOf(it.file, it.sourceName) }.filter { it.isNotBlank() }.toSet()
        val skipped = data.callSkipped.toSet()
        val result = mutableListOf<CallRecording>()
        val seen = mutableSetOf<String>()
        fun add(rec: CallRecording) {
            val key = rec.uri.ifBlank { rec.localFile?.name.orEmpty() }
            if (key.isBlank() || key in seen || key in takenUris || key in skipped) return
            seen += key
            result += rec
        }
        // 1. 应用内现场录音（无需系统权限）
        fun scanDir(dir: File) {
            val files = runCatching { dir.listFiles() }.getOrNull().orEmpty()
            android.util.Log.d("CallRecordings", "app dir ${dir.absolutePath} exists=${dir.exists()} files=${files.size}")
            files.filter { it.isFile && it.length() > 0 }.sortedByDescending { it.lastModified() }.take(50).forEach { file ->
                if (file.name in takenFiles) return@forEach
                if (!looksLikeCall(file.name, "录音", null)) {
                    android.util.Log.d("CallRecordings", "skip file ${file.name}")
                    return@forEach
                }
                add(CallRecording(uri = "", name = file.name, sizeBytes = file.length(), durationMs = wavDurationMs(file),
                    capturedAt = file.lastModified().takeIf { it > 0 }, folder = "应用内录音", localFile = file))
            }
        }
        appRecordingDirs(context).forEach(::scanDir)
        // 2. 系统媒体库音频（通话录音通常以音频文件保存；需要音频读取权限）
        if (permitted) runCatching {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.MIME_TYPE,
            )
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                var scanned = 0
                while (cursor.moveToNext() && scanned < MAX_RESULTS * 5) {
                    scanned++
                    val name = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: continue
                    val id = cursor.getLong(idColumn)
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                    if (uri in takenUris || uri in skipped || uri in seen) continue
                    val size = cursor.getLong(sizeColumn).coerceAtLeast(0)
                    if (size > 4L * 1024 * 1024 * 1024) continue
                    val duration = cursor.getLong(durationColumn).takeIf { it > 0 }
                    if (!looksLikeCall(name, "", duration)) continue
                    val added = cursor.getLong(addedColumn).takeIf { it > 0 }?.times(1000)
                    add(CallRecording(uri = uri, name = name, sizeBytes = size, durationMs = duration,
                        capturedAt = parseCapturedAt(name, added), folder = "系统音频库"))
                    if (result.size >= MAX_RESULTS) break
                }
            }
        }
        result.sortedByDescending { it.capturedAt ?: 0L }.also {
            android.util.Log.d("CallRecordings", "scan done result=${it.size}")
        }
    }

    fun matchCustomer(recordings: List<CallRecording>, customers: List<Customer>): List<CallRecording> {
        if (customers.isEmpty()) return recordings
        return recordings.map { rec ->
            if (rec.customerId.isNotBlank()) return@map rec
            val phone = parsePhone(rec.name)
            val matched = if (phone.isNotBlank()) customers.firstOrNull { normalizedPhone(it.phone) == phone } else null
            if (matched == null) rec else rec.copy(customerId = matched.id)
        }
    }

    fun scanKey(rec: CallRecording): String = rec.uri.ifBlank { rec.localFile?.name.orEmpty() }
}
