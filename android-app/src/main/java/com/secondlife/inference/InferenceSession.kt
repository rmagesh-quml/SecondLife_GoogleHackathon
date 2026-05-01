package com.secondlife.inference

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.secondlife.emergency.EmergencyRouter
import com.secondlife.emergency.ProtocolCardCache
import com.secondlife.rag.BM25Retriever
import com.secondlife.rag.ProtocolChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Populated with the final model output — useful for UI to show text as soon as inference completes.
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText

    // True once engine has finished initialising — drives the "Model ready" status indicator.
    private val _modelReady = MutableStateFlow(false)
    val modelReady: StateFlow<Boolean> = _modelReady

    // Which backend is actually running — "GpuArtisan", "GPU(OpenCL)", "CPU(8T)", or "uninitialized"
    private val _activeBackend = MutableStateFlow("uninitialized")
    val activeBackend: StateFlow<String> = _activeBackend

    private var engine: Engine? = null

    // Persistent conversation — kept alive across queries so follow-ups retain context.
    // Only reset via resetConversation() or role change.
    private var activeConversation: Conversation? = null
    private var isFirstTurn: Boolean = true

    // Serialises inference — exactly one query runs at a time, no native crashes from concurrency.
    private val inferenceMutex = Mutex()

    private val retriever = BM25Retriever(context, protocolsPath)
    private val auditLog  = KotlinAuditLog()

    var currentRole: String = "layperson"
    var currentMode: ResponseMode = ResponseMode.DETAIL

    // Hardcoded protocol chunks for the 9 most common emergencies — skip BM25 entirely.
    private val protocolChunkCache: Map<EmergencyRouter.ProtocolId, ProtocolChunk> = mapOf(
        EmergencyRouter.ProtocolId.SEIZURE to chunk("SEIZURE",
            "Seizure: Clear area of hazards. Do not restrain. Do not put anything in mouth. " +
            "Turn onto side (recovery position) if possible. Time the seizure. " +
            "Call EMS if seizure lasts >5 minutes, second seizure follows, or patient is injured/pregnant."),
        EmergencyRouter.ProtocolId.CHOKING_ADULT to chunk("CHOKING_ADULT",
            "Adult choking: If coughing forcefully — let them cough. If silent/unable to breathe: " +
            "lean forward, give 5 back blows (heel of hand between shoulder blades), " +
            "then 5 abdominal thrusts (Heimlich). Alternate until clear. If unconscious: start CPR."),
        EmergencyRouter.ProtocolId.CHOKING_INFANT to chunk("CHOKING_INFANT",
            "Infant choking (<1 year): Face-down on forearm, head lower than chest. " +
            "5 back blows with heel of hand. Flip face-up, 5 chest thrusts with 2 fingers. " +
            "Look in mouth — remove only if visible. Repeat. If unconscious: infant CPR."),
        EmergencyRouter.ProtocolId.SEVERE_BLEEDING to chunk("SEVERE_BLEEDING",
            "Severe bleeding: Apply firm direct pressure immediately. Do not remove cloth. " +
            "Elevate above heart if possible. Maintain pressure continuously. " +
            "Tourniquet above wound for limb bleeding that won't stop."),
        EmergencyRouter.ProtocolId.CPR to chunk("CPR",
            "CPR: 30 chest compressions (100-120/min, 5-6cm depth, full recoil) + 2 rescue breaths. " +
            "Push hard and fast on lower half of sternum. Use AED as soon as available. " +
            "Continue until EMS arrives or AED ready."),
        EmergencyRouter.ProtocolId.BURN to chunk("BURN",
            "Burns: Cool running water for 20 minutes — do not use ice. " +
            "Remove jewelry/clothing unless stuck. Cover loosely with cling film. " +
            "Do not burst blisters. Large/facial/airway burns need immediate EMS."),
        EmergencyRouter.ProtocolId.FRACTURE to chunk("FRACTURE",
            "Fracture: Immobilize — do not straighten. Support above and below injury. " +
            "Apply ice wrapped in cloth. Check CMS: circulation (pulse), motor (movement), sensation. " +
            "No weight-bearing. Open fractures (bone visible): cover with clean dressing."),
        EmergencyRouter.ProtocolId.ALLERGIC_REACTION to chunk("ALLERGIC_REACTION",
            "Anaphylaxis: EpiPen outer mid-thigh now. Lay flat, raise legs (unless breathing difficulty). " +
            "Call EMS immediately. Second EpiPen after 5-15 minutes if no improvement. " +
            "Antihistamines/steroids are NOT first-line — epinephrine is."),
        EmergencyRouter.ProtocolId.POISONING to chunk("POISONING",
            "Poisoning: Do NOT induce vomiting unless Poison Control says so. " +
            "Note substance, quantity, time, patient age/weight. Call Poison Control (1-800-222-1222 US). " +
            "Keep awake, monitor breathing. Recovery position if unconscious but breathing."),
    )

    // ── Init ──────────────────────────────────────────────────────────────────

    suspend fun initModel() = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext

        // Fast-fail: tell the user immediately if the model file is missing.
        // Common cause of "Loading…" forever on a second device.
        val modelFile = java.io.File(modelPath)
        if (!modelFile.exists()) {
            android.util.Log.e("InferenceSession",
                "Model file NOT FOUND at $modelPath — protocol cards will work offline; AI responses unavailable.")
            _response.value = SecondLifeResponse(
                response  = "⚠️ Model file not found at:\n$modelPath\n\n" +
                            "Protocol cards work without the model. Copy the .litertlm file to the device to enable AI responses.",
                citation  = "",
                latencyMs = 0,
                role      = currentRole,
            )
            // Mark ready so the app is fully usable with protocol-card fallback
            _modelReady.value = true
            return@withContext
        }

        try {
            val sizeMb = modelFile.length() / 1_048_576
            android.util.Log.i("InferenceSession", "Loading model from $modelPath ($sizeMb MB)…")

            // ── GPU → CPU fallback ladder ─────────────────────────────────────
            // E4B INT4 model is ~2.5 GB — fits in GPU-addressable memory on the
            // S25 Ultra (Snapdragon 8 Elite, 12 GB LPDDR5X).
            //
            // Try order:
            //   1. GpuArtisan — Samsung Artisan AI, tuned for Snapdragon 8 Elite
            //   2. GPU        — standard OpenCL path
            //   3. CPU(8T)    — guaranteed fallback, memory-mapped, never OOMs
            //
            // GPU configs use maxNumTokens=2048 to keep KV-cache VRAM overhead low.
            // CPU uses 4096 to avoid the Gemma DYNAMIC_UPDATE_SLICE crash floor.
            //
            // NOTE: if a GPU backend causes a native OOM (SIGKILL) rather than a
            // catchable exception, the OS will kill the process and Android will
            // restart the app, which will then retry from GpuArtisan again. To
            // break the loop, add a SharedPreferences flag that marks GPU as failed.
            data class Candidate(
                val backend: Backend,
                val visionBackend: Backend,
                val maxTokens: Int,
                val label: String,
            )
            val prefs = context.getSharedPreferences("inference_prefs", android.content.Context.MODE_PRIVATE)
            val gpuFailed = prefs.getBoolean("gpu_init_failed", false)

            val candidates = if (gpuFailed) {
                android.util.Log.w("InferenceSession", "GPU previously failed — starting directly on CPU(8T)")
                listOf(Candidate(Backend.CPU(numOfThreads = 8), Backend.CPU(numOfThreads = 4), 4096, "CPU(8T)"))
            } else {
                listOf(
                    Candidate(Backend.GpuArtisan(), Backend.GpuArtisan(), 2048, "GpuArtisan"),
                    Candidate(Backend.GPU(),        Backend.GPU(),        2048, "GPU(OpenCL)"),
                    Candidate(Backend.CPU(numOfThreads = 8), Backend.CPU(numOfThreads = 4), 4096, "CPU(8T)"),
                )
            }

            // Mark GPU as in-progress before we try it. If the process is killed
            // by SIGKILL (OOM), this flag persists and the next launch skips GPU.
            if (!gpuFailed) prefs.edit().putBoolean("gpu_init_failed", true).apply()

            var initialized = false
            for (candidate in candidates) {
                try {
                    android.util.Log.i("InferenceSession", "Trying ${candidate.label} backend…")
                    val config = EngineConfig(
                        modelPath     = modelPath,
                        backend       = candidate.backend,
                        visionBackend = candidate.visionBackend,
                        maxNumTokens  = candidate.maxTokens,
                        maxNumImages  = 1,
                        cacheDir      = context.cacheDir.absolutePath,
                    )
                    val e = Engine(config)
                    e.initialize()
                    engine = e
                    _activeBackend.value = candidate.label
                    // GPU succeeded — clear the failure flag so next launch tries GPU again
                    prefs.edit().putBoolean("gpu_init_failed", false).apply()
                    android.util.Log.i("InferenceSession", "✅ ${candidate.label} initialised — model ready!")
                    initialized = true
                    break
                } catch (ex: Exception) {
                    android.util.Log.w("InferenceSession",
                        "${candidate.label} failed: ${ex.message} — trying next…")
                }
            }
            if (!initialized) throw RuntimeException("All backends failed")
            _modelReady.value = true
        } catch (e: Exception) {
            android.util.Log.e("InferenceSession", "Model init failed: ${e.message}", e)
            // Mark ready so the app still works with protocol-card fallback
            _modelReady.value = true
            _response.value = SecondLifeResponse(
                response  = "⚠️ Model failed to load: ${e.message}\n\nProtocol cards are available offline.",
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
        inferenceMutex.withLock {
        _isLoading.value = true
        _streamingText.value = ""
        val startTime = System.currentTimeMillis()

        try {
            // 1. Classify deterministically — no model needed
            val protocolId = EmergencyRouter.classify(text)
            val card = ProtocolCardCache.get(protocolId)

            // 2. Fast path: known protocol + first turn + layperson role → return card instantly.
            //    Paramedics and military medics always run Gemma so they get role-specific
            //    clinical language (TCCC, MARCH, dosages) rather than plain-English card steps.
            if (card != null && isFirstTurn && image == null && currentRole == "layperson") {
                isFirstTurn = false
                val steps = card.immediateSteps
                val response = steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
                auditLog.log(text, response, currentRole)
                _response.value = SecondLifeResponse(
                    response   = response,
                    citation   = "",
                    latencyMs  = System.currentTimeMillis() - startTime,
                    role       = currentRole,
                    mode       = currentMode,
                    steps      = steps,
                    protocolId = protocolId.name,
                )
                return@withContext
            }

            // 3. Retrieve context — use cache for known protocols, BM25 for unknown
            val chunks = if (protocolId != EmergencyRouter.ProtocolId.UNKNOWN) {
                listOfNotNull(protocolChunkCache[protocolId])
            } else {
                retriever.retrieve(text, topK = 1)
            }

            // 4. Build mode-aware prompt
            val prompt = if (currentMode == ResponseMode.PANIC) {
                buildPanicPrompt(text, chunks, currentRole, hasImage = image != null)
            } else {
                buildDetailPrompt(text, chunks, currentRole, hasImage = image != null, isFirstTurn = isFirstTurn)
            }

            // 5. Run Gemma — reuse persistent conversation for follow-up turns
            val result = if (engine != null) {
                if (activeConversation == null) {
                    activeConversation = engine!!.createConversation()
                }
                val conversation = activeConversation!!

                val message = if (image != null) {
                    try {
                        val jpegBytes = bitmapToJpegBytes(image)
                        val contents  = Contents.of(
                            Content.ImageBytes(jpegBytes),
                            Content.Text(prompt),
                        )
                        conversation.sendMessage(contents, emptyMap())
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "InferenceSession",
                            "Vision sendMessage failed — falling back to text-only: ${e.message}",
                        )
                        // Fallback: use a prompt that doesn't mention the photo
                        val fallbackPrompt = if (currentMode == ResponseMode.PANIC) {
                            buildPanicPrompt(text, chunks, currentRole, hasImage = false)
                        } else {
                            buildDetailPrompt(text, chunks, currentRole, hasImage = false, isFirstTurn = isFirstTurn)
                        }
                        conversation.sendMessage(fallbackPrompt, emptyMap())
                    }
                } else {
                    conversation.sendMessage(prompt, emptyMap())
                }

                isFirstTurn = false
                val raw = (message.contents?.contents ?: emptyList())
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                    .trim()
                
                android.util.Log.d("InferenceSession", "Raw model output: \"$raw\"")
                
                val clean = if (raw.isBlank() || raw == ".") {
                    "I am processing the emergency protocol for $text. Please follow the steps below."
                } else {
                    if (currentMode == ResponseMode.DETAIL) raw.stripMarkdown() else raw
                }
                clean
            } else {
                "Model not initialized — showing protocol card guidance above."
            }

            _streamingText.value = result

            val latency  = System.currentTimeMillis() - startTime
            val citation = if (chunks.isNotEmpty() && chunks[0].source != "protocol_cache")
                "${chunks[0].source}, p.${chunks[0].page}"
            else ""

            auditLog.log(text, result, currentRole)

            // 6. Parse JSON in panic mode; use raw text in detail mode
            val finalResponse = if (currentMode == ResponseMode.PANIC) {
                parseJsonOrFallback(result, card?.immediateSteps)
            } else {
                ParsedResponse(speak = result, steps = emptyList(), ask = null)
            }

            // For non-layperson roles in DETAIL mode, Gemma has already produced
            // role-specific text in `finalResponse.speak`. Don't overwrite it with
            // hardcoded layperson card steps — that would cause TTS to speak the wrong
            // language. Only fall back to card steps for layperson (fast-path excluded
            // them, but this covers PANIC mode fallback and edge cases).
            val resolvedSteps = finalResponse.steps.ifEmpty {
                if (currentRole == "layperson") card?.immediateSteps ?: emptyList()
                else emptyList()
            }

            _response.value = SecondLifeResponse(
                response         = finalResponse.speak,
                citation         = citation,
                latencyMs        = latency,
                role             = currentRole,
                mode             = currentMode,
                steps            = resolvedSteps,
                followUpQuestion = finalResponse.ask,
                protocolId       = protocolId.name,
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
            _streamingText.value = ""
        }
        } // end inferenceMutex.withLock
    }

    suspend fun generateBroadcastPacket(
        sessionSummary: String,
        severity: Int,
        sessionId: String,
        broadcasterLat: Double,
        broadcasterLng: Double,
    ): com.secondlife.mesh.MeshManager.EmergencyBroadcast = withContext(Dispatchers.IO) {
        val safeDefaults = com.secondlife.mesh.MeshManager.EmergencyBroadcast(
            severity          = severity,
            type              = "other",
            summary           = "Emergency assistance needed",
            sessionId         = sessionId,
            respondersNeeded  = 2,
            broadcasterLat    = broadcasterLat,
            broadcasterLng    = broadcasterLng,
            broadcasterAccuracy = 0f,
        )
        try {
            if (engine == null || activeConversation == null) return@withContext safeDefaults

            val prompt = "Classify this emergency and summarize in under 10 words. " +
                "Return ONLY valid JSON, no markdown, no explanation:\n" +
                "{\"type\":\"cardiac_arrest|choking|bleeding|seizure|trauma|other\"," +
                "\"summary\":\"max 10 words\",\"responders_needed\":2}\n" +
                "Emergency: $sessionSummary"

            val conv = activeConversation ?: return@withContext safeDefaults
            val message = conv.sendMessage(prompt, emptyMap())
            val raw = (message.contents?.contents ?: emptyList())
                .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                .joinToString("") { it.text }
                .trim()

            // Strip markdown code fences if present
            val jsonStr = raw.replace(Regex("```[a-z]*\\n?"), "").replace("```", "").trim()
            val j = org.json.JSONObject(jsonStr)

            com.secondlife.mesh.MeshManager.EmergencyBroadcast(
                severity          = severity,
                type              = j.optString("type", "other"),
                summary           = j.optString("summary", "Emergency assistance needed").take(60),
                sessionId         = sessionId,
                respondersNeeded  = j.optInt("responders_needed", 2).coerceIn(1, 4),
                broadcasterLat    = broadcasterLat,
                broadcasterLng    = broadcasterLng,
                broadcasterAccuracy = 0f,
            )
        } catch (e: Exception) {
            safeDefaults
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
        _modelReady.value = false
    }

    // ── Prompt builders ───────────────────────────────────────────────────────

    // Panic mode: compact JSON, 60-120 token output, no explanations
    private fun buildPanicPrompt(
        query: String,
        chunks: List<ProtocolChunk>,
        role: String,
        hasImage: Boolean,
    ): String {
        val roleInstructions = when (role) {
            "paramedic"      -> "Clinical language. Include dosages if relevant."
            "military_medic" -> "TCCC. MARCH order. Austere environment assumed."
            else             -> "Plain English only. No jargon."
        }
        val contextLine = chunks.firstOrNull()?.text?.take(300) ?: ""
        val imageNote   = if (hasImage)
            " [PHOTO ATTACHED — analyse the visible injury/scene in the image and use it to give specific steps]"
        else ""
        return """[PANIC MODE]$imageNote
$roleInstructions
Protocol context: $contextLine

Emergency: $query

Respond ONLY with valid JSON — no other text before or after:
{"speak":"<1-2 sentence summary of the most critical immediate actions for THIS specific emergency>","steps":["specific step 1","specific step 2","specific step 3","specific step 4"],"ask":"<one follow-up question to assess severity>"}

The "speak" field must describe the actual emergency actions, not generic phrases like "follow these steps"."""
    }

    // Detail mode: full context, numbered steps with role-appropriate explanations
    private fun buildDetailPrompt(
        query: String,
        chunks: List<ProtocolChunk>,
        role: String,
        hasImage: Boolean,
        isFirstTurn: Boolean = true,
    ): String {
        val instruction = when (role) {
            "layperson" ->
                "Emergency guide. Numbered steps only. Max 6 steps. Plain English. No jargon. Address the exact emergency described."
            "paramedic" ->
                "Clinical emergency support. Address exact presentation. Clinical terms. Drug names/dosages where relevant. Numbered steps."
            "military_medic" ->
                "TCCC. MARCH protocol. Austere environment. Numbered steps. Be decisive."
            else -> "Emergency assistant. Numbered steps. Address exact emergency described."
        }

        val imageNote = if (hasImage)
            "\n[PHOTO ATTACHED — look at the image, identify the injury or emergency visible, and base your steps on what you can see.]\n"
        else
            "\nAddress this specific emergency only. Give numbered steps now.\n"

        val contextBlock = if (chunks.isNotEmpty()) {
            "\n--- Protocol reference (use ONLY if directly relevant to the emergency above) ---\n" +
            chunks.joinToString("\n\n") { "[${it.source}, p.${it.page}]\n${it.text}" } +
            "\n--- End reference ---"
        } else ""

        return if (isFirstTurn) {
            "$instruction$imageNote\n\nEmergency situation: $query\n$contextBlock\n\nImmediate steps:"
        } else {
            // Re-inject role instruction so professional roles maintain clinical language
            // through the entire conversation, not just the first turn.
            "$instruction\n\nFollow-up: $query\n\nContinue with specific steps for this exact emergency:"
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private data class ParsedResponse(val speak: String, val steps: List<String>, val ask: String?)

    private fun parseJsonOrFallback(raw: String, fallbackSteps: List<String>?): ParsedResponse {
        return try {
            // Strip markdown fences, then find the first {...} block
            val stripped = raw
                .substringAfter("```json").substringAfter("```").substringBefore("```")
                .trim()
            val jsonStart = stripped.indexOf('{')
            val jsonEnd   = stripped.lastIndexOf('}')
            val json = if (jsonStart >= 0 && jsonEnd > jsonStart)
                stripped.substring(jsonStart, jsonEnd + 1)
            else
                stripped.ifBlank { raw }
            val obj      = JSONObject(json)
            val speak    = obj.optString("speak", "").ifBlank { null }
            val stepsArr = obj.optJSONArray("steps")
            val steps    = if (stepsArr != null) (0 until stepsArr.length()).map { stepsArr.getString(it) }
                           else emptyList()
            val ask      = obj.optString("ask", "").ifBlank { null }
            // If speak is empty but we have steps, synthesize a short sentence
            val displayText = speak ?: steps.firstOrNull() ?: (fallbackSteps?.firstOrNull() ?: "Follow the steps below.")
            ParsedResponse(displayText, steps, ask)
        } catch (_: Exception) {
            // JSON parse failed — use fallback steps and avoid showing raw JSON
            val display = fallbackSteps?.firstOrNull() ?: "Follow the protocol steps."
            ParsedResponse(speak = display, steps = fallbackSteps ?: emptyList(), ask = null)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // ── Image helpers ─────────────────────────────────────────────────────────

    /** Compress Bitmap to JPEG bytes for the LiteRT-LM vision backend. */
    private fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun chunk(id: String, text: String) =
        ProtocolChunk(id = id, text = text, source = "protocol_cache", page = 0)

    private fun String.stripMarkdown(): String =
        this.replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")
            .replace(Regex("#{1,6}\\s*"), "")
            .replace(Regex("`{1,3}([^`]*)`{1,3}"), "$1")
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
            val body = JSONObject().apply {
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
            obj.keys().asSequence().sorted().joinToString("") { "$it${obj.get(it)}" }

        private fun sha256(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
