package com.singu.xfriend

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Short local cues; never network, never a notification permission dependency. */
internal object AppSounds {
    private val main = Handler(Looper.getMainLooper())

    private fun play(tone: Int, durationMs: Int) {
        val generator = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45) }.getOrNull() ?: return
        runCatching { generator.startTone(tone, durationMs) }
        main.postDelayed({ runCatching { generator.release() } }, (durationMs + 150).toLong())
    }

    fun sent() = play(ToneGenerator.TONE_PROP_ACK, 90)
    fun reply() = play(ToneGenerator.TONE_PROP_BEEP2, 140)
}
