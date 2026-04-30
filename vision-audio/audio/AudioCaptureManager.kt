package audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Standalone sensor module.
 *
 * Contract → Shravan:
 *   ByteArray — PCM signed 16-bit little-endian, 16 000 Hz, mono, no WAV headers.
 *   Silent chunks are stripped before delivery (both Flow and captureUntilSilence).
 */
class AudioCaptureManager {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        var SILENCE_THRESHOLD = 200.0   // RMS amplitude units; adjustable
    }

    @Volatile private var recording = false

    /**
     * Push-to-talk flow API.
     * Emits non-silent PCM chunks only. Completes when stopCapture() is called.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(): Flow<ByteArray> = callbackFlow {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize — microphone may be in use"
        }

        recording = true
        recorder.startRecording()

        try {
            while (isActive && recording) {
                val buffer = ByteArray(bufferSize)
                val read = recorder.read(buffer, 0, bufferSize)
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    if (rms(chunk) >= SILENCE_THRESHOLD) trySend(chunk)
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        awaitClose { recording = false }
    }

    fun stopCapture() {
        recording = false
    }

    /**
     * Records until the microphone has been silent for silenceMs milliseconds.
     * Returns the accumulated PCM buffer with trailing silence stripped.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun captureUntilSilence(silenceMs: Long = 1500): ByteArray {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize — microphone may be in use"
        }

        val output = ByteArrayOutputStream()
        // Accumulate trailing silent frames separately; discard them if silence holds.
        val silenceBuffer = ByteArrayOutputStream()
        var silenceStart = -1L
        recorder.startRecording()

        try {
            while (true) {
                val buffer = ByteArray(bufferSize)
                val read = recorder.read(buffer, 0, bufferSize)
                if (read <= 0) continue

                val chunk = buffer.copyOf(read)

                if (rms(chunk) < SILENCE_THRESHOLD) {
                    if (silenceStart < 0) silenceStart = System.currentTimeMillis()
                    silenceBuffer.write(chunk)  // stage silently
                    if (System.currentTimeMillis() - silenceStart >= silenceMs) break
                } else {
                    // Non-silent: flush any staged silence back (mid-utterance pause)
                    if (silenceBuffer.size() > 0) {
                        output.write(silenceBuffer.toByteArray())
                        silenceBuffer.reset()
                    }
                    silenceStart = -1L
                    output.write(chunk)
                }
            }
            // silenceBuffer is intentionally discarded — trailing silence stripped
        } finally {
            recorder.stop()
            recorder.release()
        }

        return output.toByteArray()
    }

    private fun rms(pcm: ByteArray): Double {
        val samples = pcm.size / 2
        if (samples == 0) return 0.0
        var sum = 0.0
        for (i in 0 until samples) {
            val sample = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort()
            sum += sample.toDouble() * sample
        }
        return sqrt(sum / samples)
    }
}
