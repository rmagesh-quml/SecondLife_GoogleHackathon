package com.secondlife.inference

data class SecondLifeResponse(
    val response: String,
    val citation: String,
    val latencyMs: Long,
    val role: String,
    val timestamp: Long = System.currentTimeMillis()
)
