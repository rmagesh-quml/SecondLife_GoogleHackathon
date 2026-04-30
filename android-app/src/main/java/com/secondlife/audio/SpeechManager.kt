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
 * Tap-to-toggle speech recognition.
 * All SpeechRecognizer calls are dispatched on the main thread.
 * Samsung ERROR_SERVER_DISCONNECTED (11) is handled with auto-retry.
 */
class SpeechManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText

    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var retryCount = 0
    private val maxRetries = 2

    fun toggle() {
        if (_isListening.value) stop() else start()
    }

    private fun start(isRetry: Boolean = false) {
        mainHandler.post {
            if (!isRetry) retryCount = 0

            // Always destroy before creating — Samsung requires this
            recognizer?.destroy()
            recognizer = null

            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    _partialText.value = ""
                    retryCount = 0
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

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
                    else onError?.invoke("No speech detected — tap mic and try again")
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    _partialText.value = ""

                    // ERROR_SERVER_DISCONNECTED (11) — common on Samsung Galaxy.
                    // Auto-retry once with a short delay.
                    if (error == 11 && retryCount < maxRetries) {
                        retryCount++
                        mainHandler.postDelayed({ start(isRetry = true) }, 600)
                        return
                    }

                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH        -> null  // silent — just tap again
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> "Listening timed out — tap mic to retry"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Offline speech not available — check Samsung Voice Input settings"
                        SpeechRecognizer.ERROR_AUDIO           -> "Microphone error — check permissions"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            // Busy — destroy and retry
                            if (retryCount < maxRetries) {
                                retryCount++
                                mainHandler.postDelayed({ start(isRetry = true) }, 800)
                            }
                            null
                        }
                        11 -> "Voice service disconnected — tap mic to retry"
                        else -> null  // swallow unknown errors silently
                    }
                    msg?.let { onError?.invoke(it) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                // Longer silence detection — useful in noisy environments
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
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
