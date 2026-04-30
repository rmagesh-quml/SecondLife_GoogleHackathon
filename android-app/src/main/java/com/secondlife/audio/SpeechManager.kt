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

    /** Called with the final recognised transcript when speech ends. */
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null

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
                override fun onRmsChanged(rmsdB: Float) {}
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
                    if (text.isNotBlank()) onResult?.invoke(text)
                    else onError?.invoke("No speech detected — try again")
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    _partialText.value = ""
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH       -> "No speech detected — try again"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out — try again"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error — enable offline speech in Settings"
                        SpeechRecognizer.ERROR_AUDIO          -> "Microphone error"
                        else                                  -> "Speech error ($error)"
                    }
                    onError?.invoke(msg)
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
            recognizer?.stopListening()
            _isListening.value = false
        }
    }

    fun destroy() {
        mainHandler.post {
            recognizer?.destroy()
            recognizer = null
        }
    }
}
