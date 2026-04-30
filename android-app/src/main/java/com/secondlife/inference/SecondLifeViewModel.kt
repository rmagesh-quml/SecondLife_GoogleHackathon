package com.secondlife.inference

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SecondLifeViewModel : ViewModel() {

    private val _response = MutableStateFlow<SecondLifeResponse?>(null)
    val response: StateFlow<SecondLifeResponse?> = _response

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setListening(listening: Boolean) {
        _isListening.value = listening
    }

    fun onInferenceResult(result: SecondLifeResponse) {
        _response.value = result
    }

    fun onError(message: String) {
        _error.value = message
    }

    fun clearError() {
        _error.value = null
    }
}
