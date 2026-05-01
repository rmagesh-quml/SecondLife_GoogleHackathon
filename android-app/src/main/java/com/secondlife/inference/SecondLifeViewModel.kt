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
import com.secondlife.mesh.MeshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SecondLifeViewModel(application: Application) : AndroidViewModel(application) {

    private val session = (application as com.secondlife.SecondLifeApplication).inferenceSession

    val isLoading:     StateFlow<Boolean> = session.isLoading
    val modelReady:    StateFlow<Boolean> = session.modelReady
    val streamingText: StateFlow<String>  = session.streamingText

    private val _capturedImage = MutableStateFlow<Bitmap?>(null)
    val capturedImage: StateFlow<Bitmap?> = _capturedImage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _handoffReport = MutableStateFlow<String?>(null)
    val handoffReport: StateFlow<String?> = _handoffReport.asStateFlow()

    val timerManager = EmergencyTimerManager(viewModelScope)
    val timerState:    StateFlow<TimerState?> = timerManager.timerState
    val metronomeBeat: StateFlow<Boolean>     = timerManager.metronomeBeat

    // ── Mesh ─────────────────────────────────────────────────────────────────
    private var meshService: MeshService? = null

    // Exposed flows for UI, backed by service data
    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private val _responderCount = MutableStateFlow(0)
    val responderCount: StateFlow<Int> = _responderCount.asStateFlow()

    private val _nearbyEmergency = MutableStateFlow<MeshManager.EmergencyBroadcast?>(null)
    val nearbyEmergency: StateFlow<MeshManager.EmergencyBroadcast?> = _nearbyEmergency.asStateFlow()

    private val _assignedTask = MutableStateFlow<String?>(null)
    val assignedTask: StateFlow<String?> = _assignedTask.asStateFlow()

    private val _pendingEndpointId = MutableStateFlow<String?>(null)
    val pendingEndpointId: StateFlow<String?> = _pendingEndpointId.asStateFlow()

    private val _isResponder = MutableStateFlow(false)
    val isResponder: StateFlow<Boolean> = _isResponder.asStateFlow()

    private val _responderTasks = MutableStateFlow<Map<String, String>>(emptyMap())
    val responderTasks: StateFlow<Map<String, String>> = _responderTasks.asStateFlow()

    val navigator = CompassNavigator(application)

    fun bindMeshService(service: MeshService) {
        this.meshService = service
        viewModelScope.launch {
            service.nearbyEmergency.collect { _nearbyEmergency.emit(it) }
        }
        viewModelScope.launch {
            service.pendingEndpointId.collect { _pendingEndpointId.emit(it) }
        }
        viewModelScope.launch {
            service.responderCount.collect { _responderCount.emit(it) }
        }
        viewModelScope.launch {
            service.isBroadcasting.collect { _isBroadcasting.emit(it) }
        }
        viewModelScope.launch {
            service.assignedTask.collect { _assignedTask.emit(it) }
        }
        viewModelScope.launch {
            service.rssi.collect { navigator.updateRSSI(it) }
        }
    }

    private val meshManager get() = meshService?.meshManager 
        ?: throw IllegalStateException("MeshService not bound")

    // ── Sessions ────────────────────────────────────────────────────────────
    private val initialSession = EmergencySession()
    private val _sessions = MutableStateFlow(listOf(initialSession))
    val sessions: StateFlow<List<EmergencySession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow(initialSession.id)
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

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

    val response: StateFlow<SecondLifeResponse?> =
        transcript.stateInMappedToResponse()

    init {}

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

    fun cancelSession() {
        timerManager.resetTimer()
        timerManager.stopMetronome()
        newSession()
    }

    fun stopTimer()    = timerManager.stopTimer()
    fun resetTimer()   = timerManager.resetTimer()
    fun stopMetronome() = timerManager.stopMetronome()

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

    /** 
     * Releases heavy resources (AI model) while keeping the app process alive.
     * This is called when the app is backgrounded so the background scanner
     * doesn't get killed by Android to reclaim RAM.
     */
    fun hibernate() {
        session.release()
    }

    override fun onCleared() {
        super.onCleared()
        timerManager.release()
        session.release()
        navigator.stopNavigation()
    }

    // ── Person A actions ──────────────────────────────────────────────────────

    fun broadcastSos() {
        if (_isBroadcasting.value) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                meshManager.stopScanning()
                val loc     = getLastKnownLocation()
                val latest  = response.value
                val summary = (latest?.steps?.take(2)?.joinToString(". ")
                               ?: latest?.response?.take(60)
                               ?: "Emergency — need help")
                val packet = MeshManager.EmergencyBroadcast(
                    severity          = latest?.severity ?: 3,
                    type              = latest?.protocolId?.lowercase()?.replace("_", " ") ?: "emergency",
                    summary           = summary.take(50),
                    sessionId         = java.util.UUID.randomUUID().toString(),
                    respondersNeeded  = 2,
                    broadcasterLat    = loc?.latitude  ?: 0.0,
                    broadcasterLng    = loc?.longitude ?: 0.0,
                    broadcasterAccuracy = loc?.accuracy ?: 0f,
                )
                meshManager.startBroadcasting(packet)
                _isBroadcasting.emit(true)
                meshService?.setIsBroadcasting(true)
            } catch (e: Exception) {
                android.util.Log.e("SecondLifeVM", "broadcastSos failed: ${e.message}", e)
                runCatching { meshManager.startScanning() }  // best-effort restart
                postError("Could not start SOS broadcast — is Bluetooth on?")
            }
        }
    }

    fun stopBroadcast() {
        meshManager.stopBroadcasting()
        viewModelScope.launch {
            _isBroadcasting.emit(false)
            meshService?.setIsBroadcasting(false)
            _responderCount.emit(0)
            _responderTasks.emit(emptyMap())
            withContext(Dispatchers.IO) { meshManager.startScanning() }
        }
    }

    // ── Person B actions ──────────────────────────────────────────────────────

    fun acceptEmergency(providedEndpointId: String? = null) {
        val broadcast   = _nearbyEmergency.value ?: return
        val endpointId  = providedEndpointId ?: _pendingEndpointId.value ?: return
        
        android.util.Log.i("SecondLifeVM", "Accepting emergency from $endpointId. Location: ${broadcast.broadcasterLat}, ${broadcast.broadcasterLng}")

        navigator.startNavigation(broadcast.broadcasterLat, broadcast.broadcasterLng)
        meshManager.joinSession(endpointId, broadcast.sessionId)
        viewModelScope.launch {
            _isResponder.emit(true)
            _nearbyEmergency.emit(null)
        }
    }

    fun dismissEmergency() {
        viewModelScope.launch {
            _nearbyEmergency.emit(null)
            _pendingEndpointId.emit(null)
        }
    }

    fun arrivedAtScene() {
        navigator.stopNavigation()
        viewModelScope.launch { _isResponder.emit(false) }
    }

    fun endSession() {
        stopBroadcast()
        meshManager.stopScanning()
        cancelSession()
    }

    // ── Demo mode ──────────────────────────────────────────────────────────────

    fun triggerDemoMesh() {
        viewModelScope.launch(Dispatchers.IO) {
            meshManager.stopScanning()
            val packet = MeshManager.EmergencyBroadcast(
                severity          = 4,
                type              = "fracture",
                summary           = "Broken leg, can't walk, needs help",
                sessionId         = java.util.UUID.randomUUID().toString(),
                respondersNeeded  = 2,
                broadcasterLat    = 0.0,
                broadcasterLng    = 0.0,
                broadcasterAccuracy = 0f,
            )
            meshManager.startBroadcasting(packet)
            meshService?.setIsBroadcasting(true)
        }
    }

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
                    broadcasterLat   = 0.0,
                    broadcasterLng   = 0.0,
                    broadcasterAccuracy = 0f,
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLastKnownLocation(): android.location.Location? =
        try {
            com.google.android.gms.tasks.Tasks.await(
                LocationServices.getFusedLocationProviderClient(getApplication()).lastLocation,
                2, TimeUnit.SECONDS
            )
        } catch (e: Exception) { null }

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
