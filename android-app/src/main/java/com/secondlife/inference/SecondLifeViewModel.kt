package com.secondlife.inference

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.secondlife.emergency.EmergencyRouter
import com.secondlife.emergency.EmergencyTimerManager
import com.secondlife.emergency.ProtocolCardCache
import com.secondlife.emergency.TimerState
import com.secondlife.mesh.CompassNavigator
import com.secondlife.mesh.MeshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

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

    // Session is owned by SecondLifeApplication — never recreated on config changes.
    private val session = (application as com.secondlife.SecondLifeApplication).inferenceSession

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

    // ── Mesh ─────────────────────────────────────────────────────────────────
    val meshManager = MeshManager(
        context              = application,
        onEmergencyReceived  = { broadcast, endpointId -> onEmergencyReceived(broadcast, endpointId) },
        onResponderJoined    = { endpointId, count -> onResponderJoined(endpointId, count) },
        onTaskReceived       = { task -> onTaskReceived(task) },
        onRSSIUpdate         = { rssi -> onRSSIUpdate(rssi) },
    )

    // Compass navigator — guides Person B toward Person A
    val navigator = CompassNavigator(application)

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private val _responderCount = MutableStateFlow(0)
    val responderCount: StateFlow<Int> = _responderCount.asStateFlow()

    private val _responderTasks = MutableStateFlow<Map<String, String>>(emptyMap())
    val responderTasks: StateFlow<Map<String, String>> = _responderTasks.asStateFlow()

    private val _nearbyEmergency = MutableStateFlow<MeshManager.EmergencyBroadcast?>(null)
    val nearbyEmergency: StateFlow<MeshManager.EmergencyBroadcast?> = _nearbyEmergency.asStateFlow()

    private val _assignedTask = MutableStateFlow<String?>(null)
    val assignedTask: StateFlow<String?> = _assignedTask.asStateFlow()

    // True once Person B has accepted a nearby emergency and is navigating
    private val _isResponder = MutableStateFlow(false)
    val isResponder: StateFlow<Boolean> = _isResponder.asStateFlow()

    // The endpoint ID of the broadcaster — stored when Person B receives alert
    private val _pendingEndpointId = MutableStateFlow<String?>(null)
    val pendingEndpointId: StateFlow<String?> = _pendingEndpointId.asStateFlow()

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
        // Model loading is started by SecondLifeApplication.onCreate() — nothing to do here.
        // Always passively scan so Person B gets alerts automatically.
        meshManager.startScanning()
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

    /** Exit the current session: clear timers and start a fresh one. */
    fun cancelSession() {
        timerManager.resetTimer()
        timerManager.stopMetronome()
        newSession()
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
        if (!session.modelReady.value) {
            postError("Model is still loading — please wait a moment")
            return
        }
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
            // Pause BLE scanning during inference — reduces thermal pressure on Snapdragon.
            // Nearby Connections + LLM simultaneously causes throttling on the S25 Ultra.
            meshManager.stopScanning()
            try {
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
            } finally {
                // Always resume scanning after inference completes or fails.
                meshManager.startScanning()
            }
        }
    }

    fun verifyAuditChain(): Boolean = session.verifyAuditChain()

    override fun onCleared() {
        super.onCleared()
        timerManager.release()
        session.release()
        meshManager.release()
        navigator.stopNavigation()
    }

    // ── Mesh callbacks (called from MeshManager on background thread) ────────

    // Person B receives a nearby SOS — store it and show the alert screen.
    private fun onEmergencyReceived(broadcast: MeshManager.EmergencyBroadcast, endpointId: String) {
        viewModelScope.launch {
            _pendingEndpointId.emit(endpointId)
            _nearbyEmergency.emit(broadcast)
        }
    }

    // Person A's side — a new responder has connected to our broadcast.
    private fun onResponderJoined(endpointId: String, count: Int) {
        viewModelScope.launch {
            _responderCount.emit(count)
            val task = meshManager.assignTask(count)
            val current = _responderTasks.value.toMutableMap()
            current[endpointId] = task
            _responderTasks.emit(current)
            meshManager.sendTaskAssignment(endpointId, task)
        }
    }

    // Person B receives their assigned task from Person A's device.
    private fun onTaskReceived(task: String) {
        viewModelScope.launch { _assignedTask.emit(task) }
    }

    // RSSI update from Nearby — forward to compass navigator for distance label.
    private fun onRSSIUpdate(rssi: Int) {
        navigator.updateRSSI(rssi)
    }

    // ── Person A actions (broadcaster — the injured person) ───────────────────

    /**
     * Manually triggered by Person A after they get first-aid guidance.
     * Packages their emergency info and broadcasts it to nearby devices.
     */
    fun broadcastSos() {
        if (_isBroadcasting.value) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loc     = getLastKnownLocation()
                val latest  = response.value
                val summary = latest?.response?.take(80) ?: "Emergency — need help"
                val severity = latest?.severity ?: 3
                val packet  = session.generateBroadcastPacket(
                    sessionSummary  = summary,
                    severity        = severity,
                    sessionId       = java.util.UUID.randomUUID().toString(),
                    broadcasterLat  = loc?.latitude  ?: 0.0,
                    broadcasterLng  = loc?.longitude ?: 0.0,
                )
                meshManager.startBroadcasting(packet)
                _isBroadcasting.emit(true)
            } catch (e: Exception) {
                android.util.Log.w("SecondLifeVM", "broadcastSos failed: ${e.message}")
                postError("Could not start SOS broadcast — check Bluetooth")
            }
        }
    }

    fun stopBroadcast() {
        meshManager.stopBroadcasting()
        viewModelScope.launch {
            _isBroadcasting.emit(false)
            _responderCount.emit(0)
            _responderTasks.emit(emptyMap())
        }
    }

    // ── Person B actions (responder — the bystander going to help) ────────────

    /**
     * Person B taps "I'll Help". Start compass navigation toward Person A
     * and join their Nearby session to receive task assignment.
     */
    fun acceptEmergency() {
        val broadcast   = _nearbyEmergency.value ?: return
        val endpointId  = _pendingEndpointId.value ?: return
        // Start compass pointing toward the broadcaster's GPS position
        navigator.startNavigation(broadcast.broadcasterLat, broadcast.broadcasterLng)
        // Connect to Person A's Nearby session
        meshManager.joinSession(endpointId, broadcast.sessionId)
        viewModelScope.launch {
            _isResponder.emit(true)
            _nearbyEmergency.emit(null)   // dismiss the alert — compass screen takes over
        }
    }

    /** Person B dismisses the alert without helping. */
    fun dismissEmergency() {
        viewModelScope.launch {
            _nearbyEmergency.emit(null)
            _pendingEndpointId.emit(null)
        }
    }

    /** Person B has physically arrived at the scene. Stop navigation. */
    fun arrivedAtScene() {
        navigator.stopNavigation()
        viewModelScope.launch { _isResponder.emit(false) }
    }

    fun endSession() {
        stopBroadcast()
        meshManager.stopScanning()
        cancelSession()
    }

    // ── Demo mode — long-press to simulate full mesh flow on one device ───────

    fun triggerDemoMesh() {
        viewModelScope.launch {
            _isBroadcasting.emit(true)
            kotlinx.coroutines.delay(3000)
            _responderCount.emit(1)
            _responderTasks.emit(mapOf("demo_1" to "Locate an AED nearby"))
            kotlinx.coroutines.delay(4000)
            _responderCount.emit(2)
            _responderTasks.emit(mapOf(
                "demo_1" to "Locate an AED nearby",
                "demo_2" to "Call 911 when signal returns",
            ))
        }
    }

    /** Demo: simulate receiving an incoming SOS as if you were Person B. */
    fun triggerDemoIncomingAlert() {
        viewModelScope.launch {
            _pendingEndpointId.emit("demo_endpoint")
            _nearbyEmergency.emit(
                MeshManager.EmergencyBroadcast(
                    severity         = 4,
                    type             = "fracture",
                    summary          = "Broken leg, can't walk, needs help",
                    sessionId        = "demo-session-001",
                    respondersNeeded = 2,
                    broadcasterLat   = 0.0,   // GPS unavailable in demo
                    broadcasterLng   = 0.0,
                    broadcasterAccuracy = 0f,
                )
            )
        }
    }

    // ── Location helper ───────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun getLastKnownLocation(): android.location.Location? =
        try {
            com.google.android.gms.tasks.Tasks.await(
                LocationServices.getFusedLocationProviderClient(getApplication()).lastLocation,
                2, TimeUnit.SECONDS
            )
        } catch (e: Exception) { null }

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

}
