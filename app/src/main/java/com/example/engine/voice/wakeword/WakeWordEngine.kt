package com.example.engine.voice.wakeword

enum class WakeWordEngineStatus {
    NOT_INITIALIZED,
    READY,
    LISTENING,
    DISABLED,
    UNSUPPORTED,
    ERROR
}

interface WakeWordEngine {
    val status: kotlinx.coroutines.flow.StateFlow<WakeWordEngineStatus>
    val targetWakeWord: String
    fun isAvailable(): Boolean
    fun startListening(onWakeWordDetected: () -> Unit)
    fun stopListening()
    fun shutdown()
}

/**
 * Clean architectural abstraction for Sherpa-ONNX local keyword spotting.
 * In Pass 5A, wake-word detection is explicitly disabled until local model files
 * are connected in Pass 5B to prevent bloated/unstable builds.
 */
class SherpaWakeWordEngine(
    override val targetWakeWord: String = "JARVIS"
) : WakeWordEngine {

    private val _status = kotlinx.coroutines.flow.MutableStateFlow(WakeWordEngineStatus.DISABLED)
    override val status: kotlinx.coroutines.flow.StateFlow<WakeWordEngineStatus> = _status

    override fun isAvailable(): Boolean {
        // Disabled until local sherpa-onnx keyword spotting model is installed in Pass 5B
        return false
    }

    override fun startListening(onWakeWordDetected: () -> Unit) {
        // Disabled in Pass 5A — will connect local model in Pass 5B
        _status.value = WakeWordEngineStatus.DISABLED
    }

    override fun stopListening() {
        if (_status.value == WakeWordEngineStatus.LISTENING) {
            _status.value = WakeWordEngineStatus.READY
        }
    }

    override fun shutdown() {
        _status.value = WakeWordEngineStatus.DISABLED
    }
}
