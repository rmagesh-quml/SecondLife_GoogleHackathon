package com.secondlife.inference

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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

    private var engine: Engine? = null

    // Persistent conversation — kept alive across queries so follow-ups
    // retain context. Only reset via resetConversation() or role change.
    private var activeConversation: Conversation? = null
    private var isFirstTurn: Boolean = true

    private val retriever = BM25Retriever(context, protocolsPath)
    private val auditLog  = KotlinAuditLog()

    var currentRole: String = "layperson"

    // ── Init ──────────────────────────────────────────────────────────────────

    suspend fun initModel() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext
        try {
            val config = EngineConfig(modelPath = modelPath)
            engine = Engine(config)
            engine?.initialize()
        } catch (e: Exception) {
            _response.value = SecondLifeResponse(
                response  = "Init Error: ${e.message}. Path: $modelPath",
                citation  = "",
                latencyMs = 0,
                role      = currentRole,
            )
        }
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
            val chunks = retriever.retrieve(text, topK = 1)
            val prompt = buildPrompt(text, chunks, currentRole,
                hasImage = image != null, isFirstTurn = isFirstTurn)

            val result = if (engine != null) {
                // Create conversation on first turn, reuse on follow-ups
                if (activeConversation == null) {
                    activeConversation = engine!!.createConversation()
                }
                val conversation = activeConversation!!
                // sendMessage(String, Map) is the synchronous API — returns Message
                val message = conversation.sendMessage(prompt, emptyMap())
                isFirstTurn = false
                // Extract all text parts from the response contents
                message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                    .ifBlank { "No response generated" }
                    .stripMarkdown()
            } else {
                "Model not initialized — check model file path"
            }

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

    private fun closeActiveConversation() {
        val conv = activeConversation ?: return
        activeConversation = null
        try { conv.close() } catch (_: Exception) {}
    }

    fun verifyAuditChain(): Boolean = auditLog.verifyChain()

    fun clearResponse() { _response.value = null }

    /** Start a fresh conversation — call when switching emergencies or roles. */
    fun resetConversation() {
        closeActiveConversation()
        isFirstTurn = true
    }

    fun release() {
        closeActiveConversation()
        engine = null
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    private fun buildPrompt(
        query: String,
        chunks: List<ProtocolChunk>,
        role: String,
        hasImage: Boolean,
        isFirstTurn: Boolean = true,
    ): String {
        val instruction = when (role) {
            "layperson" ->
                "You are SecondLife, an emergency medical assistant. " +
                "The person in front of you has NO medical training and is likely panicking. " +
                "You MUST address the specific injury or emergency they describe — do NOT give generic first aid. " +
                "Use plain, simple English. Write numbered steps (1. 2. 3. etc). " +
                "Start with the single most critical action. Be specific, detailed, and calm. " +
                "Do not use markdown formatting like ** or *. Write plain text only."
            "paramedic" ->
                "You are SecondLife, a clinical decision-support tool for a trained paramedic. " +
                "Address the specific presentation described — do NOT give generic protocols. " +
                "Use clinical terminology. Include drug names and dosages where relevant. " +
                "Be thorough and precise. Number your steps. Do not use ** markdown."
            "military_medic" ->
                "You are SecondLife, a TCCC assistant for a military medic in the field. " +
                "Apply MARCH protocol to the SPECIFIC injury described. " +
                "Assume austere environment, limited supplies. Be direct and decisive. " +
                "Number every action. Do not use ** markdown."
            else -> "You are an emergency assistant. Give specific, numbered instructions for the exact emergency described. Plain text only."
        }

        val imageNote = if (hasImage)
            "\nThe responder has visually assessed the scene and photographed the injury. " +
            "Treat this as confirmed visual information. Address ONLY the specific injury described below. " +
            "Do NOT ask for photos, more information, or suggest calling a doctor — give actionable steps NOW.\n"
        else
            "\nAddress the SPECIFIC emergency described below. Do not give generic first aid. " +
            "Do not ask for more information. Give specific numbered steps now.\n"

        val contextBlock = if (chunks.isNotEmpty()) {
            "\n--- Protocol reference (use ONLY if directly relevant to the emergency above) ---\n" +
            chunks.joinToString("\n\n") { "[${it.source}, p.${it.page}]\n${it.text}" } +
            "\n--- End reference ---"
        } else ""

        // Query comes FIRST so the model anchors on the actual emergency,
        // not the RAG context. Context follows as optional background only.
        return if (isFirstTurn) {
            "$instruction$imageNote\n\nEmergency situation: $query\n$contextBlock\n\nImmediate steps:"
        } else {
            "Follow-up: $query\n\nContinue with specific steps for this exact emergency:"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Strip markdown so the model's **bold** and *italic* render as plain text on screen. */
    private fun String.stripMarkdown(): String =
        this.replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")   // **bold**, *italic*, ***both***
            .replace(Regex("#{1,6}\\s*"), "")                    // ## headings
            .replace(Regex("`{1,3}([^`]*)`{1,3}"), "$1")        // `code`
            .trim()

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
