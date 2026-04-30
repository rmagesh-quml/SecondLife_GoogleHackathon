package com.secondlife.inference

/**
 * Output of the full AI pipeline (RAG + Gemma 4 + role prompt).
 * Emitted as StateFlow<SecondLifeResponse?> by SecondLifeViewModel.
 *
 * Contract: Shravan → Sid — see shared/contracts.md
 *
 * Pipeline: voice → AudioCaptureManager → InferenceSession → StateFlow → TTS
 */
data class SecondLifeResponse(
    val response:   String,  // answer text — display in UI and speak via TTS
    val citation:   String,  // e.g. "sepsis_bundle.pdf, p.4" — "" if no RAG hit
    val latencyMs:  Long,    // end-to-end ms from query to first complete token
    val role:       String,  // "layperson" | "paramedic" | "military_medic"
    val timestamp:  Long = System.currentTimeMillis()
)
