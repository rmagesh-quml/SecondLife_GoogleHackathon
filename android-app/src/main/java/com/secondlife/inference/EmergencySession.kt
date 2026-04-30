package com.secondlife.inference

import java.util.UUID

data class EmergencySession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = DEFAULT_TITLE,
    val createdAt: Long = System.currentTimeMillis(),
    val role: String = "layperson",
    val sessionStartedAt: Long? = null,
    val transcript: List<TranscriptTurn> = emptyList(),
) {
    companion object {
        const val DEFAULT_TITLE = "New emergency"

        fun titleFromQuery(query: String, maxLen: Int = 48): String {
            val trimmed = query.trim()
            return if (trimmed.length <= maxLen) trimmed
            else trimmed.take(maxLen - 1).trimEnd() + "…"
        }
    }
}
