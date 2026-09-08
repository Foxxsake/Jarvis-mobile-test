package com.example.engine.voice

enum class VoiceSessionState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    PROCESSING,
    WAITING_FOR_APPROVAL,
    SPEAKING,
    ERROR
}

interface VoiceSessionListener {
    fun onStateChanged(state: VoiceSessionState)
    fun onSpeechRecognized(text: String)
    fun onError(message: String)
}

/**
 * Coordinates the full voice conversation lifecycle:
 * LISTENING -> TRANSCRIBING -> PROCESSING -> WAITING_FOR_APPROVAL -> SPEAKING -> IDLE / ERROR
 *
 * Prevents audio feedback loops by pausing/stopping speech recognition while JARVIS speaks,
 * and handles spoken response delivery according to user settings.
 */
class VoiceSessionController(
    private val speechManager: com.example.engine.speech.SpeechManager,
    private val voiceOutput: JarvisVoiceOutput
) {

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(VoiceSessionState.IDLE)
    val state: kotlinx.coroutines.flow.StateFlow<VoiceSessionState> = _state

    private var spokenResponsesEnabled = true
    private var listener: VoiceSessionListener? = null

    fun setListener(listener: VoiceSessionListener?) {
        this.listener = listener
    }

    fun setSpokenResponsesEnabled(enabled: Boolean) {
        this.spokenResponsesEnabled = enabled
    }

    fun isSpokenResponsesEnabled(): Boolean = spokenResponsesEnabled

    /**
     * Updates internal state and notifies listener.
     */
    private fun updateState(newState: VoiceSessionState) {
        _state.value = newState
        listener?.onStateChanged(newState)
    }

    /**
     * User or trigger requested to start listening.
     * Ensures any previous speech output is stopped before starting listening.
     */
    fun startListening() {
        voiceOutput.stop()
        updateState(VoiceSessionState.LISTENING)
        speechManager.startListening()
    }

    /**
     * Stops listening immediately.
     */
    fun stopListening() {
        speechManager.stopListening()
        if (_state.value == VoiceSessionState.LISTENING) {
            updateState(VoiceSessionState.IDLE)
        }
    }

    /**
     * Handle incoming speech manager state changes from the platform.
     */
    fun handleSpeechState(speechState: com.example.engine.speech.SpeechState) {
        when (speechState) {
            is com.example.engine.speech.SpeechState.Ready -> {
                if (_state.value == VoiceSessionState.LISTENING || _state.value == VoiceSessionState.TRANSCRIBING) {
                    updateState(VoiceSessionState.IDLE)
                }
            }
            is com.example.engine.speech.SpeechState.Listening -> {
                updateState(VoiceSessionState.LISTENING)
            }
            is com.example.engine.speech.SpeechState.Processing -> {
                updateState(VoiceSessionState.TRANSCRIBING)
            }
            is com.example.engine.speech.SpeechState.Success -> {
                updateState(VoiceSessionState.PROCESSING)
                listener?.onSpeechRecognized(speechState.text)
            }
            is com.example.engine.speech.SpeechState.Error -> {
                updateState(VoiceSessionState.ERROR)
                listener?.onError(speechState.message)
            }
            is com.example.engine.speech.SpeechState.PermissionRequired -> {
                updateState(VoiceSessionState.ERROR)
                listener?.onError("Microphone permission required")
            }
            is com.example.engine.speech.SpeechState.Unavailable -> {
                updateState(VoiceSessionState.ERROR)
                listener?.onError("Speech recognition unavailable")
            }
        }
    }

    /**
     * Called when a command requires user approval.
     */
    fun onWaitingForApproval(promptText: String? = null) {
        updateState(VoiceSessionState.WAITING_FOR_APPROVAL)
        if (spokenResponsesEnabled && !promptText.isNullOrBlank()) {
            speakResponse(promptText) {
                // Return to waiting for approval state after speaking finishes
                updateState(VoiceSessionState.WAITING_FOR_APPROVAL)
            }
        }
    }

    /**
     * Speaks a response aloud, ensuring speech recognizer is paused/stopped to avoid feedback loops.
     */
    fun speakResponse(text: String, onDone: (() -> Unit)? = null) {
        if (!spokenResponsesEnabled) {
            onDone?.invoke()
            return
        }

        // Cycle: Stop recognition before speaking to prevent JARVIS hearing itself
        speechManager.stopListening()
        updateState(VoiceSessionState.SPEAKING)

        voiceOutput.speak(text) {
            if (_state.value == VoiceSessionState.SPEAKING) {
                updateState(VoiceSessionState.IDLE)
            }
            onDone?.invoke()
        }
    }

    /**
     * Stops speaking immediately.
     */
    fun stopSpeaking() {
        voiceOutput.stop()
        if (_state.value == VoiceSessionState.SPEAKING) {
            updateState(VoiceSessionState.IDLE)
        }
    }

    /**
     * Resets session to IDLE.
     */
    fun reset() {
        speechManager.stopListening()
        voiceOutput.stop()
        updateState(VoiceSessionState.IDLE)
    }

    /**
     * Shuts down resources.
     */
    fun shutdown() {
        speechManager.destroyRecognizer()
        voiceOutput.shutdown()
        updateState(VoiceSessionState.IDLE)
    }
}
