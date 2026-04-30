package com.secondlife.inference

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.lm.LlmInference
import com.secondlife.rag.BM25Retriever
import com.secondlife.rag.ProtocolChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

class InferenceSession(
    private val context: Context,
    private val modelPath: String,
    protocolsPath: String? = null,
) {
    private val _response  = MutableStateFlow<SecondLifeResponse?>(null)
    val response: StateFlow<SecondLifeResponse?> = _response

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var llmInference: LlmInference? = null
    private val retriever  = BM25Retriever(context, protocolsPath)
    private val auditLog   = KotlinAuditLog()

    var currentRole: String = "layperson"

    // ── Init ──────────────────────────────────────────────────────────────────

    suspend fun initModel() = withContext(Dispatchers.IO) {
        if (llmInference != null) return@withContext
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .setTopK(40)
            .setTemperature(1.0f)
            .build()
        llmInference = LlmInference.createFromOptions(context, options)
    }

    // ── Respond ───────────────────────────────────────────────────────────────

    suspend fun respond(
        text: String,
        audio: Any? = null,
        image: Bitmap? = null,
    ) = withContext(Dispatchers.IO) {
        _isLoading.value = true
        val startTime = System.currentTimeMillis()

        try {
            val chunks   = retriever.retrieve(text, topK = 3)
            val prompt   = buildPrompt(text, chunks, currentRole, hasImage = image != null)
            val result   = llmInference?.generateResponse(prompt) ?: "Model not initialized"
            val latency  = System.currentTimeMillis() - startTime
            val citation = if (chunks.isNotEmpty())
                "${chunks[0].source}, p.${chunks[0].page}"
            else ""

            auditLog.log(text, result, currentRole)

            _response.value = SecondLifeResponse(
                response  = result,
                citation  = citation,
                latencyMs = latency,
                role      = currentRole,
            )
        } catch (e: Exception) {
            _response.value = SecondLifeResponse(
                response  = "Error: ${e.message}",
                citation  = "",
                latencyMs = 0,
                role      = currentRole,
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

    // ── Prompt ────────────────────────────────────────────────────────────────

    private fun buildPrompt(
        query: String,
        chunks: List<ProtocolChunk>,
        role: String,
        hasImage: Boolean,
    ): String {
        val instruction = when (role) {
            "layperson"      -> "You are a calm emergency guide for someone with zero medical training. " +
                                "Use plain English. Number every step. Lead with the most important action first."
            "paramedic"      -> "You are a clinical decision-support tool for a trained paramedic. " +
                                "Use clinical language, include drug names and dosages where relevant. Be concise."
            "military_medic" -> "You are a TCCC assistant for a military medic. " +
                                "Follow MARCH protocol order. Assume austere environment. Be direct and decisive."
            else             -> "Provide clear, numbered emergency instructions."
        }

        val imageNote = if (hasImage)
            "\nThe user has also captured a photo of the scene or injury. " +
            "Factor in that they have a visual of the situation when giving guidance.\n"
        else ""

        val contextBlock = if (chunks.isNotEmpty()) {
            "\nRelevant protocol context:\n" +
            chunks.joinToString("\n\n") { "[${it.source}, p.${it.page}]\n${it.text}" }
        } else ""

        return "[ROLE: $role]\n$instruction$imageNote$contextBlock\n\nEmergency: $query\nNumbered steps:"
    }

    // ── Audit log ─────────────────────────────────────────────────────────────

    private class KotlinAuditLog {
        private val GENESIS_HASH = "0".repeat(64)
        private val chain = mutableListOf<AuditEntry>()

        data class AuditEntry(
            val query: String, val response: String, val role: String,
            val timestamp: Long, val prevHash: String, val hash: String,
        )

        fun log(query: String, response: String, role: String) {
            val timestamp = System.currentTimeMillis()
            val prevHash  = if (chain.isEmpty()) GENESIS_HASH else chain.last().hash
            val body      = JSONObject().apply {
                put("prev_hash", prevHash); put("query", query)
                put("response", response); put("role", role); put("timestamp", timestamp)
            }
            val hash = sha256(sortedJson(body))
            chain.add(AuditEntry(query, response, role, timestamp, prevHash, hash))
        }

        fun verifyChain(): Boolean {
            var lastHash = GENESIS_HASH
            for (entry in chain) {
                if (entry.prevHash != lastHash) return false
                val body = JSONObject().apply {
                    put("prev_hash", entry.prevHash); put("query", entry.query)
                    put("response", entry.response); put("role", entry.role)
                    put("timestamp", entry.timestamp)
                }
                if (sha256(sortedJson(body)) != entry.hash) return false
                lastHash = entry.hash
            }
            return true
        }

        private fun sortedJson(obj: JSONObject): String =
            obj.keys().asSequence().sorted()
                .joinToString("") { key -> "$key${obj.get(key)}" }

        private fun sha256(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
