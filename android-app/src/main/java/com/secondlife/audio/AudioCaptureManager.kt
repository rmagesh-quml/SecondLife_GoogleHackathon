package com.secondlife.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive

/**
 * Captures PCM 16-bit 16kHz mono audio from the microphone.
 *
 * Contract (Rohan → Shravan):
 *   Emits ByteArray chunks of raw PCM — no WAV headers, no compression.
 *   Silence trimming is applied before emission.
 *
 * Usage:
 *   AudioCaptureManager(context).captureFlow().collect { pcmChunk -> ... }
 */
class AudioCaptureManager(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_THRESHOLD = 500  // amplitude units
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun captureFlow(): Flow<ByteArray> = callbackFlow {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        recorder.startRecording()

        try {
            while (isActive) {
                val buffer = ByteArray(bufferSize)
                val read = recorder.read(buffer, 0, bufferSize)
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    if (!isSilent(chunk)) {
                        trySend(chunk)
                    }
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        awaitClose()
    }

    private fun isSilent(pcm: ByteArray): Boolean {
        var sum = 0L
        for (i in pcm.indices step 2) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            sum += Math.abs(sample.toInt())
        }
        val avg = sum / (pcm.size / 2)
        return avg < SILENCE_THRESHOLD
    }
}
