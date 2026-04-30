package com.secondlife.inference

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.secondlife.rag.BM25Retriever
import com.secondlife.rag.ProtocolChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.*

class InferenceSession(private val context: Context, private val modelPath: String, protocolsPath: String? = null) {

    private val _response = MutableStateFlow<SecondLifeResponse?>(null)
    val response: StateFlow<SecondLifeResponse?> = _response

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var llmInference: LlmInference? = null
    private val retriever = BM25Retriever(context, protocolsPath)
    private val auditLog = KotlinAuditLog()

    var currentRole: String = "layperson"

    suspend fun initModel() = withContext(Dispatchers.IO) {
        if (llmInference == null) {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setTopK(40)
                .setTemperature(1.0f)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
        }
    }

    suspend fun respond(text: String, audio: Any? = null, image: Any? = null) = withContext(Dispatchers.IO) {
        _isLoading.value = true
        val startTime = System.currentTimeMillis()
        try {
            val chunks = retriever.retrieve(text, topK = 3)
            val fullPrompt = buildPrompt(text, chunks, currentRole)
            
            val result = llmInference?.generateResponse(fullPrompt) ?: "Model not initialized"
            val latency = System.currentTimeMillis() - startTime
            
            auditLog.log(text, result, currentRole)
            
            val secondLifeResponse = SecondLifeResponse(
                response = result,
                citation = chunks.joinToString("\n") { "[${it.id}] ${it.source} p.${it.page}" },
                latencyMs = latency,
                role = currentRole
            )
            _response.value = secondLifeResponse
        } catch (e: Exception) {
            _response.value = SecondLifeResponse(
                response = "Error: ${e.message}",
                citation = "",
                latencyMs = 0,
                role = currentRole
            )
        } finally {
            _isLoading.value = false
        }
    }

    fun verifyAuditChain(): Boolean = auditLog.verifyChain()

    fun release() {
        llmInference?.close()
        llmInference = null
    }

    private fun buildPrompt(query: String, chunks: List<ProtocolChunk>, role: String): String {
        val systemInstruction = when (role) {
            "layperson" -> "Provide simple, urgent instructions for a non-medical person. Use common terms."
            "professional" -> "Provide technical, precise medical instructions for a first responder. Use medical terminology."
            else -> "Provide clear emergency instructions."
        }

        val contextText = if (chunks.isNotEmpty()) {
            "Retrieved medical context:\n" + chunks.joinToString("\n\n") { it.text }
        } else {
            "No specific context available. Use general emergency protocols."
        }

        return "[ROLE: $role]\n$systemInstruction\n\n$contextText\n\nEmergency: $query\nNumbered steps:"
    }

    private class KotlinAuditLog {
        private val GENESIS_HASH = "0" * 64
        private val chain = mutableListOf<AuditEntry>()

        data class AuditEntry(
            val query: String,
            val response: String,
            val role: String,
            val timestamp: Long,
            val prevHash: String,
            val hash: String
        )

        fun log(query: String, response: String, role: String) {
            val timestamp = System.currentTimeMillis()
            val prevHash = if (chain.isEmpty()) GENESIS_HASH else chain.last().hash
            val entryData = JSONObject().apply {
                put("query", query)
                put("response", response)
                put("role", role)
                put("timestamp", timestamp)
                put("prev_hash", prevHash)
            }
            // Sort keys as in Python
            val sortedKeys = entryData.keys().asSequence().sorted()
            val sb = StringBuilder()
            for (key in sortedKeys) {
                sb.append(key).append(entryData.get(key))
            }
            val hash = sha256(sb.toString())
            chain.add(AuditEntry(query, response, role, timestamp, prevHash, hash))
        }

        fun verifyChain(): Boolean {
            if (chain.isEmpty()) return true
            var lastHash = GENESIS_HASH
            for (entry in chain) {
                if (entry.prevHash != lastHash) return false
                val entryData = JSONObject().apply {
                    put("query", entry.query)
                    put("response", entry.response)
                    put("role", entry.role)
                    put("timestamp", entry.timestamp)
                    put("prev_hash", entry.prevHash)
                }
                val sortedKeys = entryData.keys().asSequence().sorted()
                val sb = StringBuilder()
                for (key in sortedKeys) {
                    sb.append(key).append(entryData.get(key))
                }
                if (sha256(sb.toString()) != entry.hash) return false
                lastHash = entry.hash
            }
            return true
        }

        private fun sha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private operator fun String.times(n: Int): String = repeat(n)
    }
}
