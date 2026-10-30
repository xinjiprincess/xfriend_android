package com.singu.xfriend

import android.content.Context
import androidx.compose.runtime.*
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

internal class SalesPlayback(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        setHandleAudioBecomingNoisy(true)
    }
    var recordId by mutableStateOf(""); private set
    var title by mutableStateOf(""); private set
    var playing by mutableStateOf(false); private set
    var position by mutableLongStateOf(0); private set
    var duration by mutableLongStateOf(0); private set
    var speed by mutableFloatStateOf(1f); private set
    var error by mutableStateOf(""); private set
    var peaks by mutableStateOf<List<Float>>(emptyList()); private set
    private var waveJob: Job? = null
    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlayerError(value: PlaybackException) { error = "录音暂时无法播放，请检查文件是否仍在本机" }
        })
        scope.launch { while (isActive) { position = player.currentPosition.coerceAtLeast(0); duration = player.duration.coerceAtLeast(0); delay(250) } }
    }
    fun play(file: File, id: String, name: String) {
        require(file.isFile) { "录音文件不存在" }
        if (recordId != id) {
            recordId = id; title = name; error = ""; peaks = emptyList(); duration = 0; position = 0
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file))); player.prepare()
            waveJob?.cancel()
            waveJob = scope.launch { peaks = withContext(Dispatchers.IO) { runCatching { waveformSamples(file) }.getOrDefault(emptyList()) } }
        }
        player.play()
    }
    fun toggle() { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }
    fun seek(milliseconds: Long) { player.seekTo(if (duration > 0) milliseconds.coerceIn(0, duration) else milliseconds.coerceAtLeast(0)); position = player.currentPosition.coerceAtLeast(0) }
    fun rate(value: Float) { speed = value.coerceIn(.75f, 2f); player.setPlaybackSpeed(speed) }
    fun stop() { player.stop(); player.clearMediaItems(); recordId = ""; position = 0; duration = 0; playing = false; waveJob?.cancel() }
    fun release() { scope.cancel(); player.release() }
}

/** Bounded thumbnail sampling: never decode an entire recording into memory. */
internal fun waveformSamples(file: File, count: Int = 120): List<Float> {
    wavInfo(file)
    return RandomAccessFile(file, "r").use { input ->
        val bytes = (input.length() - 44).coerceAtLeast(0)
        val samples = (0 until count).map { index ->
            input.seek(44 + ((bytes * index / count) / 2 * 2))
            val window = ByteArray(minOf(1024L, input.length() - input.filePointer).toInt())
            input.readFully(window)
            var peak = 0
            for (i in 0 until window.size - 1 step 2) peak = maxOf(peak, abs(((window[i].toInt() and 255) or (window[i + 1].toInt() shl 8)).toShort().toInt()))
            peak / 32768f
        }
        val maximum = samples.maxOrNull()?.coerceAtLeast(.01f) ?: 1f
        samples.map { (it / maximum).coerceIn(.04f, 1f) }
    }
}
