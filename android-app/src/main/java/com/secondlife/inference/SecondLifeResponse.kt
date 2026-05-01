package com.secondlife.inference

enum class ResponseMode { PANIC, DETAIL }

data class SecondLifeResponse(
    val response: String,
    val citation: String,
    val latencyMs: Long,
    val role: String,
    val mode: ResponseMode = ResponseMode.PANIC,
    val steps: List<String> = emptyList(),
    val followUpQuestion: String? = null,
    val protocolId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
