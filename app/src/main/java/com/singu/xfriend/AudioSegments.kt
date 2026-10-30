package com.singu.xfriend

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val MAX_AUDIO_SECONDS = 4 * 60 * 60
internal const val SEGMENT_SECONDS = 90

/** Bounded memory PCM16 downmix/resample; averages source frames instead of duplicating channels. */
internal class SpeechPcm(private val rate: Int, private val channels: Int) {
    init { require(rate in 8000..192000 && channels in 1..8) { "不支持此采样率或声道数" } }
    private var remainder = ByteArray(0)
    private var frames = 0L
    private var emitted = 0L
    private var sum = 0L
    private var count = 0
    fun convert(bytes: ByteArray): ByteArray {
        val input = remainder + bytes
        val frameSize = channels * 2
        val complete = input.size / frameSize * frameSize
        remainder = input.copyOfRange(complete, input.size)
        val output = java.io.ByteArrayOutputStream()
        val b = ByteBuffer.wrap(input, 0, complete).order(ByteOrder.LITTLE_ENDIAN)
        while (b.remaining() >= frameSize) {
            var mono = 0
            repeat(channels) { mono += b.short.toInt() }
            mono /= channels
            sum += mono; count++; frames++
            val target = frames * 16000 / rate
            while (emitted < target) {
                val value = (if (count > 0) sum / count else mono.toLong()).toInt().coerceIn(-32768, 32767)
                output.write(value and 255); output.write((value shr 8) and 255)
                emitted++
            }
            if (target > 0 && emitted == target && frames * 16000 % rate < 16000) { sum = 0; count = 0 }
        }
        return output.toByteArray()
    }
}

internal data class WavInfo(val rate: Int, val channels: Int, val bytes: Int) {
    val segmentBytes get() = rate * channels * 2 * SEGMENT_SECONDS
    val segments get() = (bytes.toLong() + segmentBytes - 1).div(segmentBytes).toInt()
}
internal fun wavInfo(file: File): WavInfo = RandomAccessFile(file, "r").use { f ->
    val header = ByteArray(44); f.readFully(header)
    val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE" && String(header, 36, 4) == "data" && b.getShort(20).toInt() == 1 && b.getShort(34).toInt() == 16) { "请重新导入录音，以转换为标准 WAV" }
    val info = WavInfo(b.getInt(24), b.getShort(22).toInt(), b.getInt(40))
    require(info.rate in 8000..192000 && info.channels in 1..8 && info.bytes > 0 && info.bytes.toLong() + 44 == file.length() &&
        info.bytes % (info.channels * 2) == 0 && b.getInt(28) == info.rate * info.channels * 2 && b.getShort(32).toInt() == info.channels * 2) { "录音文件损坏或尚未写入完成" }
    info
}
internal fun audioSegment(file: File, index: Int): ByteArray {
    val info = wavInfo(file)
    require(index in 0 until info.segments)
    // Legacy high-rate WAVs are also downmixed before upload.
    val size = minOf(info.segmentBytes.toLong(), info.bytes.toLong() - index.toLong() * info.segmentBytes).toInt()
    val normalizer = SpeechPcm(info.rate, info.channels)
    val output = java.io.ByteArrayOutputStream()
    RandomAccessFile(file, "r").use { f ->
        f.seek(44L + index.toLong() * info.segmentBytes)
        var remaining = size
        val buffer = ByteArray(65536)
        while (remaining > 0) {
            val n = minOf(buffer.size, remaining)
            f.readFully(buffer, 0, n)
            output.write(normalizer.convert(buffer.copyOf(n)))
            remaining -= n
        }
    }
    val normalized = output.toByteArray()
    return wavHeader(normalized.size) + normalized
}

/** Frame ranges keep subsecond tails and overlap without changing the legacy segment API. */
internal data class ReviewAudioRange(val startFrame: Long, val endFrame: Long, val rate: Int) {
    init { require(rate > 0 && startFrame >= 0 && endFrame > startFrame) }
    val startSeconds get() = startFrame.toDouble() / rate
    val seconds get() = (endFrame - startFrame).toDouble() / rate
    val key get() = "$startFrame-$endFrame"
}
internal fun reviewAudioRanges(info: WavInfo): List<ReviewAudioRange> {
    val frames = info.bytes.toLong() / (info.channels * 2)
    require(frames <= MAX_AUDIO_SECONDS.toLong() * info.rate) { "单次支持最长 4 小时录音" }
    return buildList {
        var start = 0L
        while (start < frames) {
            val end = minOf(frames, start + SEGMENT_SECONDS.toLong() * info.rate)
            add(ReviewAudioRange(start, end, info.rate))
            if (end == frames) break
            start = end - 2L * info.rate
        }
    }
}
internal fun splitReviewAudio(range: ReviewAudioRange): List<ReviewAudioRange> {
    require(range.seconds > 5)
    val middle = (range.startFrame + range.endFrame) / 2
    val overlap = minOf(range.rate.toLong(), (range.endFrame - range.startFrame) / 8)
    return listOf(range.copy(endFrame = middle + overlap), range.copy(startFrame = middle - overlap))
}
internal fun reviewAudio(file: File, range: ReviewAudioRange): ByteArray {
    val info = wavInfo(file)
    require(range.rate == info.rate && range.endFrame <= info.bytes.toLong() / (info.channels * 2))
    val converter = SpeechPcm(info.rate, info.channels)
    val output = java.io.ByteArrayOutputStream()
    RandomAccessFile(file, "r").use { input ->
        input.seek(44 + range.startFrame * info.channels * 2)
        var remaining = (range.endFrame - range.startFrame) * info.channels * 2
        val buffer = ByteArray(65536)
        while (remaining > 0) {
            val count = minOf(buffer.size.toLong(), remaining).toInt()
            input.readFully(buffer, 0, count)
            output.write(converter.convert(buffer.copyOf(count)))
            remaining -= count
        }
    }
    val pcm = output.toByteArray()
    return wavHeader(pcm.size) + pcm
}
