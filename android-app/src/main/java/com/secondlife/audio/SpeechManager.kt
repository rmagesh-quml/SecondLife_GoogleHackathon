package com.secondlife.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Wraps Android SpeechRecognizer for in-app, no-dialog speech-to-text.
 * Tap once to start listening, tap again (or wait for silence) to get result.
 *
 * SpeechRecognizer must be created and called on the main thread — all calls
 * are dispatched via mainHandler automatically.
 */
class SpeechManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText

    /**
     * Normalised microphone level in 0f..1f, updated continuously while listening.
     * Drives the waveform visualiser. Decays to 0 when not listening.
     */
    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel

    /** Called with the final recognised transcript when speech ends. */
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null

    // Set to true when stop() is called intentionally so that the ERROR_CLIENT
    // callback fired by stopListening() doesn't surface as a user-visible toast.
    private var stoppingIntentionally = false

    // Set to true while an auto-retry is pending (error 11 — audio session busy).
    // Prevents cascading retries if the second attempt also fails.
    private var autoRetrying = false

    fun toggle() {
        if (_isListening.value) stop() else start()
    }

    private fun start() {
        mainHandler.post {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    _partialText.value = ""
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    // Android reports RMS roughly in -2..10 dB. Map to 0..1.
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _rmsLevel.value = normalized
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    _partialText.value = text
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    _isListening.value = false
                    _partialText.value = ""
                    _rmsLevel.value    = 0f
                    // Empty result is normal (user was silent) — don't show an error toast
                    if (text.isNotBlank()) onResult?.invoke(text)
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    _partialText.value = ""
                    _rmsLevel.value    = 0f

                    // Errors caused by our own stop() call or routine non-speech events
                    // should never reach the user as a toast.
                    if (stoppingIntentionally) {
                        stoppingIntentionally = false
                        return
                    }
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,       // user didn't speak — silent reset
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT, // timed out — silent reset
                        SpeechRecognizer.ERROR_CLIENT,         // fired after destroy() — suppress
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY // already running — suppress
                        -> return
                        11 -> {
                            // ERROR_SERVER_DISCONNECTED — audio session was still held by TTS
                            // (or another audio focus owner) when the recognizer tried to start.
                            // Retry once after 350ms to let the audio system settle.
                            if (!autoRetrying) {
                                autoRetrying = true
                                mainHandler.postDelayed({
                                    autoRetrying = false
                                    start()
                                }, 350)
                            }
                            return
                        }
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            onError?.invoke("Network error — enable offline speech in Settings")
                        SpeechRecognizer.ERROR_AUDIO ->
                            onError?.invoke("Microphone error — check permissions")
                        else ->
                            onError?.invoke("Speech error ($error)")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Prefer offline if available
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            recognizer?.startListening(intent)
        }
    }

    fun stop() {
        mainHandler.post {
            stoppingIntentionally = true
            recognizer?.stopListening()
            _isListening.value = false
            _rmsLevel.value    = 0f
            // Clear the flag after a short window — any ERROR_CLIENT fired by
            // stopListening() arrives within a few ms; 300ms is a safe buffer.
            mainHandler.postDelayed({ stoppingIntentionally = false }, 300)
        }
    }

    fun destroy() {
        mainHandler.post {
            recognizer?.destroy()
            recognizer = null
        }
    }
}
