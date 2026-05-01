package com.secondlife.inference

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secondlife.emergency.EmergencyRouter
import com.secondlife.emergency.EmergencyTimerManager
import com.secondlife.emergency.ProtocolCardCache
import com.secondlife.emergency.TimerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel that owns the list of [EmergencySession]s.
 *
 * ─── Backend gaps the UI cannot close on its own ──────────────────────────
 * The session list is purely UI-side. To make sessions feel "real" end-to-end
 * the backend would need:
 *   1. [InferenceSession.respond] to accept a session id (or a per-session
 *      Conversation) so two emergencies don't share a single live `_response`
 *      / `_isLoading` flow.
 *   2. The prompt builder to include prior-turn context for the active
 *      session (currently each call builds a fresh prompt with zero history,
 *      so the model has no per-session memory — only the UI does).
 *   3. The audit log chain to be partitioned by session id (currently a
 *      single global chain across all sessions).
 *   4. Persistence (Room / DataStore) so sessions survive process death.
 * None of these are touched by this PR — they are intentionally listed here
 * for whoever owns `ai-pipeline/` and the audit-log code.
 */
class SecondLifeViewModel(application: Application) : AndroidViewModel(application) {

    private val modelPath     = resolveModelPath(application)
    private val protocolsPath = resolveProtocolsPath(application)
    private val session       = InferenceSession(application, modelPath, protocolsPath)

    val isLoading:     StateFlow<Boolean> = session.isLoading
    val modelReady:    StateFlow<Boolean> = session.modelReady
    val streamingText: StateFlow<String>  = session.streamingText

    // Captured camera frame — staged across queries until cleared.
    private val _capturedImage = MutableStateFlow<Bitmap?>(null)
    val capturedImage: StateFlow<Bitmap?> = _capturedImage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Handoff report generated async — never blocks the emergency path.
    private val _handoffReport = MutableStateFlow<String?>(null)
    val handoffReport: StateFlow<String?> = _handoffReport.asStateFlow()

    // Native timers / CPR metronome — all off the model path.
    val timerManager = EmergencyTimerManager(viewModelScope)
    val timerState:    StateFlow<TimerState?> = timerManager.timerState
    val metronomeBeat: StateFlow<Boolean>     = timerManager.metronomeBeat

    // ── Sessions ────────────────────────────────────────────────────────────
    private val initialSession = EmergencySession()

    private val _sessions = MutableStateFlow(listOf(initialSession))
    val sessions: StateFlow<List<EmergencySession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow(initialSession.id)
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    // Derived flows: always read the active session's slice.
    val transcript: StateFlow<List<TranscriptTurn>> =
        _sessions.combine(_activeSessionId) { ss, id ->
            ss.firstOrNull { it.id == id }?.transcript ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val role: StateFlow<String> =
        _sessions.combine(_activeSessionId) { ss, id ->
            ss.firstOrNull { it.id == id }?.role ?: "layperson"
        }.stateIn(viewModelScope, SharingStarted.Eagerly, "layperson")

    val sessionStartedAt: StateFlow<Long?> =
        _sessions.combine(_activeSessionId) { ss, id ->
            ss.firstOrNull { it.id == id }?.sessionStartedAt
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The latest completed response on the *active* session — drives TTS.
    val response: StateFlow<SecondLifeResponse?> =
        transcript.stateInMappedToResponse()

    init {
        // Preload model at app start — not on first query.
        viewModelScope.launch { session.initModel() }
    }

    // ── Camera ──────────────────────────────────────────────────────────────
    fun setCapturedImage(bitmap: Bitmap?) { _capturedImage.value = bitmap }
    fun clearCapturedImage()              { _capturedImage.value = null }

    // ── Error snackbar ──────────────────────────────────────────────────────
    fun dismissError()         { _error.value = null }
    fun postError(msg: String) { _error.value = msg }

    // ── Sessions API ────────────────────────────────────────────────────────
    fun newSession() {
        val s = EmergencySession()
        _sessions.value         = _sessions.value + s
        _activeSessionId.value  = s.id
        session.currentRole     = s.role
        session.resetConversation()
        _capturedImage.value    = null
        _handoffReport.value    = null
    }

    fun selectSession(id: String) {
        if (_sessions.value.none { it.id == id }) return
        _activeSessionId.value = id
        session.currentRole    = activeOrNull()?.role ?: "layperson"
    }

    fun deleteSession(id: String) {
        val remaining = _sessions.value.filter { it.id != id }
        if (remaining.isEmpty()) {
            val fresh = EmergencySession()
            _sessions.value        = listOf(fresh)
            _activeSessionId.value = fresh.id
        } else {
            _sessions.value = remaining
            if (_activeSessionId.value == id) {
                _activeSessionId.value = remaining.first().id
            }
        }
        session.currentRole = activeOrNull()?.role ?: "layperson"
    }

    fun setRole(role: String) {
        updateActive { it.copy(role = role) }
        session.currentRole = role
    }

    fun setMode(mode: ResponseMode) {
        session.currentMode = mode
    }

    /** Stop TTS / listening, clear image, stop timers, reset conversation. */
    fun cancelSession() {
        _capturedImage.value  = null
        _handoffReport.value  = null
        timerManager.stopTimer()
        timerManager.stopMetronome()
        session.resetConversation()
    }

    // ── Timer / metronome ────────────────────────────────────────────────────
    fun stopTimer()    = timerManager.stopTimer()
    fun resetTimer()   = timerManager.resetTimer()
    fun stopMetronome() = timerManager.stopMetronome()

    // ── Handoff report ───────────────────────────────────────────────────────

    /** Build a handoff report from the latest response. Call only on explicit user tap. */
    fun generateHandoffReport() {
        val r = response.value ?: return
        viewModelScope.launch {
            val report = buildString {
                appendLine("=== SecondLife Handoff Report ===")
                appendLine("Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(r.timestamp))}")
                appendLine("Role: ${r.role.replace("_", " ").replaceFirstChar { it.uppercase() }}")
                appendLine("Protocol: ${r.protocolId ?: "General"}")
                appendLine()
                appendLine("Steps taken:")
                r.steps.forEachIndexed { i, step -> appendLine("  ${i + 1}. $step") }
                if (r.steps.isEmpty()) appendLine("  ${r.response}")
                appendLine()
                if (r.citation.isNotBlank()) appendLine("Source: ${r.citation}")
                appendLine("Response time: ${r.latencyMs} ms")
                r.followUpQuestion?.let { appendLine("Pending assessment: $it") }
            }
            _handoffReport.value = report
        }
    }

    fun dismissHandoffReport() { _handoffReport.value = null }

    // ── Query ────────────────────────────────────────────────────────────────

    /**
     * Run a query on the *active* session. Captures the active id at dispatch
     * time so switching sessions while the model is thinking still routes the
     * response back to the originating session.
     */
    fun query(text: String, audio: Any? = null) {
        if (text.isBlank()) return
        _handoffReport.value = null
        val capturedId  = _activeSessionId.value
        val pendingTurn = TranscriptTurn(userText = text, response = null)

        updateActive { sess ->
            sess.copy(
                transcript       = sess.transcript + pendingTurn,
                sessionStartedAt = sess.sessionStartedAt ?: System.currentTimeMillis(),
                title            = if (sess.title == EmergencySession.DEFAULT_TITLE && sess.transcript.isEmpty())
                                       EmergencySession.titleFromQuery(text)
                                   else sess.title,
            )
        }

        val image = _capturedImage.value
        viewModelScope.launch {
            session.respond(text, audio = audio, image = image)
            val completed = session.response.value ?: return@launch

            _sessions.value = _sessions.value.map { sess ->
                if (sess.id != capturedId) return@map sess
                sess.copy(
                    transcript = sess.transcript.map { t ->
                        if (t.id == pendingTurn.id) t.copy(response = completed) else t
                    }
                )
            }

            // Auto-start native timer for protocols that need one — no model involved.
            val protocolId = completed.protocolId?.let {
                runCatching { EmergencyRouter.ProtocolId.valueOf(it) }.getOrNull()
            }
            val card = protocolId?.let { ProtocolCardCache.get(it) }
            card?.timerLabel?.let { label ->
                if (protocolId == EmergencyRouter.ProtocolId.CPR) timerManager.startMetronome()
                else timerManager.startTimer(label, hint = card.timerHint)
            }
        }
    }

    fun verifyAuditChain(): Boolean = session.verifyAuditChain()

    override fun onCleared() {
        super.onCleared()
        timerManager.release()
        session.release()
    }

    // ── Internals ───────────────────────────────────────────────────────────
    private fun activeOrNull(): EmergencySession? =
        _sessions.value.firstOrNull { it.id == _activeSessionId.value }

    private fun updateActive(transform: (EmergencySession) -> EmergencySession) {
        val id = _activeSessionId.value
        _sessions.value = _sessions.value.map { if (it.id == id) transform(it) else it }
    }

    private fun StateFlow<List<TranscriptTurn>>.stateInMappedToResponse(): StateFlow<SecondLifeResponse?> {
        return MutableStateFlow<SecondLifeResponse?>(null).also { out ->
            viewModelScope.launch {
                collect { turns -> out.value = turns.lastOrNull { it.response != null }?.response }
            }
        }
    }

    companion object {
        private fun resolveModelPath(app: Application): String {
            val dirs = listOf(
                "/data/local/tmp/",
                "/sdcard/Download/models/",
                "/storage/emulated/0/Download/models/",
                app.filesDir.absolutePath + "/",
            )
            for (dir in dirs) {
                val f = File(dir, "gemma-4-E4B-it.litertlm")
                if (f.exists()) return f.absolutePath
            }
            return File(app.filesDir, "gemma-4-E4B-it.litertlm").absolutePath
        }

        private fun resolveProtocolsPath(app: Application): String? {
            val f = File(app.filesDir, "protocols.json")
            return if (f.exists()) f.absolutePath else null
        }
    }
}
