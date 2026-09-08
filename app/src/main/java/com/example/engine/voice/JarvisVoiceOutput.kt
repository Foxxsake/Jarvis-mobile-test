package com.example.engine.voice

sealed class VoiceOutputState {
    object Idle : VoiceOutputState()
    object Initializing : VoiceOutputState()
    object Ready : VoiceOutputState()
    data class Speaking(val text: String) : VoiceOutputState()
    data class Error(val message: String) : VoiceOutputState()
    object Unavailable : VoiceOutputState()
}

interface JarvisVoiceOutput {
    val state: kotlinx.coroutines.flow.StateFlow<VoiceOutputState>
    fun isAvailable(): Boolean
    fun speak(text: String, onDone: (() -> Unit)? = null)
    fun stop()
    fun shutdown()
}
