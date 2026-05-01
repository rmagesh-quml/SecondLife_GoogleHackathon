package com.secondlife.ui

import android.graphics.Bitmap
import com.secondlife.emergency.TimerState
import com.secondlife.inference.EmergencySession
import com.secondlife.inference.ResponseMode
import com.secondlife.inference.SecondLifeResponse
import com.secondlife.inference.TranscriptTurn

data class MainUiState(
    val role: String = "layperson",
    val isOnDevice: Boolean = true,
    val signalLabel: String = "No signal",
    val sessionElapsedMs: Long? = null,
    val rmsLevel: Float = 0f,
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val partialTranscript: String = "",
    val transcript: List<TranscriptTurn> = emptyList(),
    val capturedImage: Bitmap? = null,
    val error: String? = null,
    val sessions: List<EmergencySession> = emptyList(),
    val activeSessionId: String? = null,
    val streamingText: String = "",
    val timerState: TimerState? = null,
    val metronomeBeat: Boolean = false,
    val handoffReport: String? = null,
    val selectedMode: ResponseMode = ResponseMode.PANIC,
) {
    val latestResponse: SecondLifeResponse?
        get() = transcript.lastOrNull { it.response != null }?.response

    val statusLabel: String
        get() = when {
            isListening -> "Listening…"
            isThinking  -> "Thinking…"
            isSpeaking  -> "Speaking…"
            else        -> "Ready"
        }
}

data class MainUiCallbacks(
    val onMicTap: () -> Unit = {},
    val onCameraTap: () -> Unit = {},
    val onCancelTap: () -> Unit = {},
    val onRoleChange: (String) -> Unit = {},
    val onClearImage: () -> Unit = {},
    val onErrorDismiss: () -> Unit = {},
    val onMenuOpen: () -> Unit = {},
    val onNewSession: () -> Unit = {},
    val onSelectSession: (String) -> Unit = {},
    val onDeleteSession: (String) -> Unit = {},
    val onModeChange: (ResponseMode) -> Unit = {},
    val onStopTimer: () -> Unit = {},
    val onResetTimer: () -> Unit = {},
    val onGenerateReport: () -> Unit = {},
    val onDismissReport: () -> Unit = {},
)
