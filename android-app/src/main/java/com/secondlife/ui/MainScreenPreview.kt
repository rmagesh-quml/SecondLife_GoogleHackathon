package com.secondlife.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.secondlife.inference.EmergencySession
import com.secondlife.inference.SecondLifeResponse
import com.secondlife.inference.TranscriptTurn
import com.secondlife.ui.theme.SecondLifeTheme

private fun fakeSession(role: String = "layperson") = EmergencySession(
    title = "Chest pain, 45-year-old male",
    role  = role,
    sessionStartedAt = System.currentTimeMillis() - 90_000L,
    transcript = listOf(
        TranscriptTurn(
            userText = "Patient is unresponsive, no pulse",
            response = SecondLifeResponse(
                role     = role,
                response = "1. Call 911 immediately\n2. Begin chest compressions\n3. Push hard and fast — 2 inches deep at 100-120 bpm",
                keyTerms = listOf("chest compressions", "911"),
            ),
        )
    ),
)

private val idleState = MainUiState()

private val listeningState = MainUiState(
    isListening = true,
    rmsLevel    = 0.6f,
)

private val thinkingState = MainUiState(
    isThinking      = true,
    partialTranscript = "Patient has stopped breathing",
)

private val speakingState = MainUiState(
    isSpeaking = true,
    sessions   = listOf(fakeSession()),
    transcript = fakeSession().transcript,
    activeSessionId = fakeSession().id,
)

private val militaryState = MainUiState(
    role       = "military_medic",
    sessions   = listOf(fakeSession("military_medic")),
    transcript = fakeSession("military_medic").transcript,
    activeSessionId = fakeSession("military_medic").id,
)

private val errorState = MainUiState(
    error = "Microphone permission denied — check app settings",
)

@Preview(name = "Idle", showBackground = true, backgroundColor = 0xFF06080F)
@Composable
private fun PreviewIdle() {
    SecondLifeTheme { MainScreenContent(state = idleState, callbacks = MainUiCallbacks()) }
}

@Preview(name = "Listening", showBackground = true, backgroundColor = 0xFF06080F)
@Composable
private fun PreviewListening() {
    SecondLifeTheme { MainScreenContent(state = listeningState, callbacks = MainUiCallbacks()) }
}

@Preview(name = "Thinking", showBackground = true, backgroundColor = 0xFF06080F)
@Composable
private fun PreviewThinking() {
    SecondLifeTheme { MainScreenContent(state = thinkingState, callbacks = MainUiCallbacks()) }
}

@Preview(name = "Speaking + transcript", showBackground = true, backgroundColor = 0xFF06080F)
@Composable
private fun PreviewSpeaking() {
    SecondLifeTheme { MainScreenContent(state = speakingState, callbacks = MainUiCallbacks()) }
}

@Preview(name = "Military medic", showBackground = true, backgroundColor = 0xFF06080F)
@Composable
private fun PreviewMilitary() {
    SecondLifeTheme { MainScreenContent(state = militaryState, callbacks = MainUiCallbacks()) }
}

@Preview(name = "Error snackbar", showBackground = true, backgroundColor = 0xFF06080F)
@Composable
private fun PreviewError() {
    SecondLifeTheme { MainScreenContent(state = errorState, callbacks = MainUiCallbacks()) }
}

@Preview(name = "Drawer only", showBackground = true, backgroundColor = 0xFF06080F)
@Composable
private fun PreviewDrawer() {
    SecondLifeTheme {
        EmergencySessionsDrawer(
            state     = speakingState,
            callbacks = MainUiCallbacks(),
        )
    }
}
