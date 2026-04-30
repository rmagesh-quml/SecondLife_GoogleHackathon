package com.secondlife.inference

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class SecondLifeViewModel(application: Application) : AndroidViewModel(application) {

    private val modelPath: String = determineModelPath()
    private val protocolsPath: String? = determineProtocolsPath()

    private val session = InferenceSession(application, modelPath, protocolsPath)

    val response: StateFlow<SecondLifeResponse?> = session.response
    val isLoading: StateFlow<Boolean> = session.isLoading

    init {
        viewModelScope.launch {
            session.initModel()
        }
    }

    fun query(text: String, audio: Any? = null, image: Any? = null) {
        viewModelScope.launch {
            session.respond(text, audio, image)
        }
    }

    fun setRole(role: String) {
        session.currentRole = role
    }

    fun verifyAuditChain(): Boolean {
        return session.verifyAuditChain()
    }

    override fun onCleared() {
        super.onCleared()
        session.release()
    }

    private fun determineModelPath(): String {
        val emulatorPath = "/data/local/tmp/gemma-4-E4B-it-web.task"
        val devicePath = File(getApplication<Application>().filesDir, "gemma-4-E4B-it-web.task").absolutePath
        
        return if (File(emulatorPath).exists()) {
            emulatorPath
        } else {
            devicePath
        }
    }

    private fun determineProtocolsPath(): String? {
        val path = File(getApplication<Application>().filesDir, "protocols.json").absolutePath
        return if (File(path).exists()) path else null
    }
}
