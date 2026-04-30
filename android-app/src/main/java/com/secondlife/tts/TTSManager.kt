package com.secondlife.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Wraps Android TextToSpeech.
 * Speaks only the `response` field of SecondLifeResponse — never citation or latency.
 */
class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(0.95f)
            ready = true
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "secondlife_response")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }
}
