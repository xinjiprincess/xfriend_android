package com.singu.xfriend

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun wavHeader(size: Int, rate: Int = 16000, channels: Int = 1): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort()); putInt(rate); putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16); put("data".toByteArray()); putInt(size)
}.array()

internal class PhoneRecorder {
    @Volatile private var running = false
    @Volatile private var paused = false
    private var audio: AudioRecord? = null
    fun stop() { running = false }
    fun setPaused(value: Boolean) { paused = value }
    @SuppressLint("MissingPermission")
    suspend fun record(file: File, onSeconds: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val bufferSize = maxOf(4096, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val mic = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        audio = mic
        try {
            check(mic.state == AudioRecord.STATE_INITIALIZED) { "麦克风不可用" }; running = true; paused = false; mic.startRecording()
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(0); output.write(wavHeader(0)); val buffer = ByteArray(bufferSize); var bytes = 0
                while (running && bytes < MAX_AUDIO_SECONDS * 32000) {
                    if (paused) {
                        mic.stop()
                        while (paused && running) kotlinx.coroutines.delay(50)
                        if (!running) break
                        mic.startRecording()
                    }
                    val n = mic.read(buffer, 0, minOf(buffer.size, MAX_AUDIO_SECONDS * 32000 - bytes)); check(n >= 0) { "录音被系统中断" }
                    if (n > 0) { output.write(buffer, 0, n); bytes += n; onSeconds(bytes / 32000) }
                }
                output.seek(0); output.write(wavHeader(bytes)); require(bytes > 3200) { "录音过短，请重试" }
            }
        } catch (e: Exception) { file.delete(); throw e }
        finally { running = false; paused = false; runCatching { mic.stop() }; mic.release(); audio = null }
    }
}

// MiMo ASR accepts WAV/MP3. Android system recordings often use M4A/AAC.
internal fun decodeAudio(context: Context, uri: Uri, target: File, progress: (Int) -> Unit = {}) {
    val extractor = MediaExtractor(); var codec: MediaCodec? = null
    try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: error("文件没有音轨")
        extractor.selectTrack(track); val format = extractor.getTrackFormat(track)
        require(!format.containsKey(MediaFormat.KEY_DURATION) || format.getLong(MediaFormat.KEY_DURATION) <= MAX_AUDIO_SECONDS * 1_000_000L) { "单次支持最长 4 小时录音，请按会议分开导入" }
        if (format.getString(MediaFormat.KEY_MIME) == "audio/raw") {
            require(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "请导入 PCM16 WAV、MP3 或 M4A 音频" }
            val normalizer = SpeechPcm(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            RandomAccessFile(target, "rw").use { output ->
                output.setLength(0); output.write(wavHeader(0))
                val buffer = ByteBuffer.allocate(256 * 1024)
                var bytes = 0; var announced = -1
                while (true) {
                    buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    buffer.position(0); val raw = ByteArray(size); buffer.get(raw)
                    val chunk = normalizer.convert(raw); bytes += chunk.size
                    require(bytes <= MAX_AUDIO_SECONDS * 32000) { "录音超过 4 小时" }
                    output.write(chunk)
                    if (bytes / 320000 != announced) { announced = bytes / 320000; progress(bytes / 32000) }
                    extractor.advance()
                }
                require(bytes > 0) { "录音内容为空" }; output.seek(0); output.write(wavHeader(bytes))
            }
            return
        }
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec = decoder; decoder.configure(format, null, null, 0); decoder.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var inputDone = false; var outputDone = false; var bytes = 0; var emptyPolls = 0
        val info = MediaCodec.BufferInfo()
        var normalizer = SpeechPcm(rate, channels)
        var announced = -1
        RandomAccessFile(target, "rw").use { output ->
            output.setLength(0); output.write(wavHeader(0))
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("已取消")
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10000)
                    if (i >= 0) { val b = decoder.getInputBuffer(i)!!; val n = extractor.readSampleData(b, 0)
                        if (n < 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val i = decoder.dequeueOutputBuffer(info, 10000)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat; rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    normalizer = SpeechPcm(rate, channels)
                    require(!f.containsKey(MediaFormat.KEY_PCM_ENCODING) || f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "不支持此音频编码" }
                } else if (i >= 0) {
                    emptyPolls = 0; val b = decoder.getOutputBuffer(i)!!; b.position(info.offset); b.limit(info.offset + info.size)
                    val raw = ByteArray(info.size); b.get(raw); val chunk = normalizer.convert(raw)
                    bytes += chunk.size; require(bytes <= MAX_AUDIO_SECONDS * 32000) { "录音超过 4 小时" }; output.write(chunk)
                    val seconds = bytes / 32000
                    if (seconds / 10 != announced) { announced = seconds / 10; progress(seconds) }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; decoder.releaseOutputBuffer(i, false)
                } else { emptyPolls++; check(emptyPolls < 3000) { "音频解码超时" } }
            }
            require(bytes > 0); output.seek(0); output.write(wavHeader(bytes))
        }
    } catch (e: Exception) { target.delete(); throw e }
    finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
}
internal fun importImage(context: Context, uri: Uri, file: File) {
    // Keep the imported source intact. Bitmap decoders inspect the header, not the .jpg suffix.
    // Analysis reads region tiles; a long screenshot is never globally downsampled.
    try {
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
            val buffer = ByteArray(65536); var bytes = 0L
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                bytes += count; require(bytes <= 50L * 1024 * 1024) { "图片文件超过 50 MB" }
                output.write(buffer, 0, count)
            }
        } }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
    } catch (e: Exception) { file.delete(); throw e }
}
@Suppress("DEPRECATION")
internal fun reviewImageTiles(source: File, tileFile: (String) -> File, checkpoint: () -> Unit): List<File> {
    val decoder = android.graphics.BitmapRegionDecoder.newInstance(source.path, false)
    val created = mutableListOf<File>()
    try {
        for (region in reviewImageRegions(decoder.width, decoder.height)) {
            checkpoint()
            val target = tileFile(source.nameWithoutExtension + "-" + region.suffix + ".jpg")
            created.add(target)
            val bitmap = decoder.decodeRegion(android.graphics.Rect(region.left, region.top, region.right, region.bottom), BitmapFactory.Options())
                ?: error("图片区域无法解码")
            try { target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } } finally { bitmap.recycle() }
            require(target.length() <= 10 * 1024 * 1024) { "图片区域过大" }
        }
        return created
    } catch (e: Exception) { created.forEach { it.delete() }; throw e }
    finally { decoder.recycle() }
}
internal fun phoneContacts(context: Context): List<Customer> {
    val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    return context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, null, null, "${columns[0]} ASC")?.use { c ->
        buildList { while (c.moveToNext() && size < 1000) { val phone = c.getString(1).orEmpty(); add(Customer(name = c.getString(0).orEmpty().ifBlank { phone }, phone = phone)) } }
    } ?: emptyList()
}
internal fun customerSms(context: Context, phone: String): String {
    require(normalizedPhone(phone).length >= 7) { "先为客户填写电话号码" }
    return context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("address", "body", "type"), null, null, "date DESC")?.use { c ->
        val lines = mutableListOf<String>(); var scanned = 0
        while (c.moveToNext() && lines.size < 100 && scanned++ < 5000) if (normalizedPhone(c.getString(0).orEmpty()) == normalizedPhone(phone)) lines.add((if (c.getInt(2) == 1) "客户：" else "我：") + c.getString(1).orEmpty())
        lines.reversed().joinToString("\n").take(12000)
    }.orEmpty()
}
