package com.secondlife.emergency

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class TimerState(
    val label: String,
    val elapsedMs: Long,
    val running: Boolean,
)

class EmergencyTimerManager(private val scope: CoroutineScope) {

    private val _timerState = MutableStateFlow<TimerState?>(null)
    val timerState: StateFlow<TimerState?> = _timerState

    private val _metronomeBeat = MutableStateFlow(false)
    val metronomeBeat: StateFlow<Boolean> = _metronomeBeat

    private var timerJob: Job? = null
    private var metronomeJob: Job? = null

    fun startTimer(label: String) {
        timerJob?.cancel()
        val startMs = System.currentTimeMillis()
        _timerState.value = TimerState(label, 0L, running = true)
        timerJob = scope.launch {
            while (true) {
                delay(1_000L)
                val elapsed = System.currentTimeMillis() - startMs
                _timerState.value = TimerState(label, elapsed, running = true)
            }
        }
    }

    fun stopTimer() {
        timerJob?.cancel()
        _timerState.value = _timerState.value?.copy(running = false)
    }

    fun resetTimer() {
        timerJob?.cancel()
        _timerState.value = null
    }

    // CPR metronome at 100 BPM (600 ms interval). Toggles metronomeBeat each tick
    // so the UI can flash/pulse in sync.
    fun startMetronome() {
        metronomeJob?.cancel()
        metronomeJob = scope.launch {
            while (true) {
                _metronomeBeat.value = true
                delay(100L)
                _metronomeBeat.value = false
                delay(500L) // 600 ms total = 100 BPM
            }
        }
    }

    fun stopMetronome() {
        metronomeJob?.cancel()
        _metronomeBeat.value = false
    }

    fun release() {
        timerJob?.cancel()
        metronomeJob?.cancel()
    }

    companion object {
        fun formatElapsed(elapsedMs: Long): String {
            val totalSeconds = elapsedMs / 1_000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }
}
