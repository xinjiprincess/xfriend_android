package com.singu.xfriend

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** Local device speech for the call screen. Text never leaves the device through this class. */
internal class StarSpeech(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var onDone: (() -> Unit)? = null
    private var utterance: String = ""

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.SIMPLIFIED_CHINESE
                engine?.setSpeechRate(1.0f)
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = finish(id)
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = finish(id)
                })
            }
        }
    }

    private fun finish(id: String?) {
        if (id != null && id != utterance) return
        val callback = onDone
        onDone = null
        main.post { callback?.invoke() }
    }

    fun speak(text: String, done: () -> Unit) {
        val clean = text.trim().take(4000)
        if (!ready || clean.isBlank()) { done(); return }
        onDone = done
        utterance = "star-call-${UUID.randomUUID()}"
        val result = engine?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utterance) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) { onDone = null; done() }
    }

    fun stop() {
        onDone = null
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }
}
