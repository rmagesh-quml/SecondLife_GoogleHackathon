package com.secondlife.inference

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
        viewModelScope.launch {
            session.respond(text, audio = audio, image = image)
            // Keep the image staged so judges can see it during the demo; user can tap × to clear
        }
    }

    fun setRole(role: String) {
        session.currentRole = role
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
            val emulator = File("/data/local/tmp/gemma-4-E4B-it.litertlm")
            val device   = File(app.filesDir, "gemma-4-E4B-it.litertlm")
            return when {
                emulator.exists() -> emulator.absolutePath
                else              -> device.absolutePath
            }
        }

        private fun resolveProtocolsPath(app: Application): String? {
            val f = File(app.filesDir, "protocols.json")
            return if (f.exists()) f.absolutePath else null
        }
    }
}
