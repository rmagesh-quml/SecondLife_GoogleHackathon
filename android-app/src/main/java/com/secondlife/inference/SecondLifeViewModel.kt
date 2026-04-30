package com.secondlife.inference

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class SecondLifeViewModel(application: Application) : AndroidViewModel(application) {

    private val modelPath     = resolveModelPath(application)
    private val protocolsPath = resolveProtocolsPath(application)
    private val session       = InferenceSession(application, modelPath, protocolsPath)

    val response:  StateFlow<SecondLifeResponse?> = session.response
    val isLoading: StateFlow<Boolean>             = session.isLoading

    // Captured camera frame — set by MainActivity, cleared after each query
    private val _capturedImage = MutableStateFlow<Bitmap?>(null)
    val capturedImage: StateFlow<Bitmap?> = _capturedImage

    // Error snackbar text
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Track the active query so we can cancel it on newEmergency() / setRole()
    private var activeQueryJob: Job? = null

    init {
        viewModelScope.launch { session.initModel() }
    }

    fun setCapturedImage(bitmap: Bitmap?) {
        _capturedImage.value = bitmap
    }

    fun clearCapturedImage() {
        _capturedImage.value = null
    }

    fun query(text: String, audio: Any? = null) {
        val image = _capturedImage.value   // snapshot — include whatever is staged
        activeQueryJob?.cancel()
        activeQueryJob = viewModelScope.launch {
            session.respond(text, audio = audio, image = image)
            // Keep image staged so judges can see it; user taps × to clear
        }
    }

    fun setRole(role: String) {
        // Cancel any in-flight query so it can't overwrite the new context
        activeQueryJob?.cancel()
        session.resetConversation()
        session.currentRole = role
        session.clearResponse()
    }

    /** Start fresh — new emergency, wipe context, clear image and response. */
    fun newEmergency() {
        // Cancel in-flight query first — prevents old response bleeding into new session
        activeQueryJob?.cancel()
        session.resetConversation()
        session.clearResponse()
        _capturedImage.value = null
    }

    fun verifyAuditChain(): Boolean = session.verifyAuditChain()

    fun dismissError() { _error.value = null }

    fun postError(msg: String) { _error.value = msg }

    override fun onCleared() {
        super.onCleared()
        session.release()
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
            // Fallback path — app will show an init error with the path so you know where to push
            return File(app.filesDir, "gemma-4-E4B-it.litertlm").absolutePath
        }

        private fun resolveProtocolsPath(app: Application): String? {
            val f = File(app.filesDir, "protocols.json")
            return if (f.exists()) f.absolutePath else null
        }
    }
}
