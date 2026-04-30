package com.secondlife.inference

data class TranscriptTurn(
    val id: Long = System.nanoTime(),
    val userText: String,
    val response: SecondLifeResponse?,
    val createdAt: Long = System.currentTimeMillis(),
)
