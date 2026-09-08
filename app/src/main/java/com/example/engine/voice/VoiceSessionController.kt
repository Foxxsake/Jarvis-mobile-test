package com.example.engine.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 *
 * Supports hands-free continuous listening mode with automatic loop re-arming.
 */
class VoiceSessionController(
    private val speechManager: com.example.engine.speech.SpeechManager,
    private val voiceOutput: JarvisVoiceOutput
) {

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rearmJob: Job? = null

    private val _state = MutableStateFlow(VoiceSessionState.IDLE)
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private var spokenResponsesEnabled = true
    private var handsFreeMode = false
    private var listener: VoiceSessionListener? = null

    fun setListener(listener: VoiceSessionListener?) {
        this.listener = listener
    }

    fun setSpokenResponsesEnabled(enabled: Boolean) {
        this.spokenResponsesEnabled = enabled
    }

    fun isSpokenResponsesEnabled(): Boolean = spokenResponsesEnabled

    fun setHandsFreeMode(enabled: Boolean) {
        this.handsFreeMode = enabled
        rearmJob?.cancel()
        if (enabled) {
            if (_state.value == VoiceSessionState.IDLE) {
                startListening()
            }
        } else {
            if (_state.value == VoiceSessionState.LISTENING) {
                stopListening()
            }
        }
    }

    fun isHandsFreeMode(): Boolean = handsFreeMode

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
        rearmJob?.cancel()
        voiceOutput.stop()
        updateState(VoiceSessionState.LISTENING)
        speechManager.startListening()
    }

    /**
     * Stops listening immediately.
     */
    fun stopListening() {
        rearmJob?.cancel()
        speechManager.stopListening()
        if (_state.value == VoiceSessionState.LISTENING) {
            updateState(VoiceSessionState.IDLE)
        }
    }

    /**
     * Schedule re-arming of the microphone for continuous hands-free operation.
     */
    private fun scheduleHandsFreeRearm(delayMs: Long = 300L) {
        if (!handsFreeMode) return
        rearmJob?.cancel()
        rearmJob = controllerScope.launch {
            delay(delayMs)
            if (handsFreeMode && _state.value == VoiceSessionState.IDLE) {
                startListening()
            }
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
                    if (handsFreeMode) {
                        scheduleHandsFreeRearm(200L)
                    }
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
                if (handsFreeMode && speechState.isTransient) {
                    // Transient timeout/silence in continuous mode: re-arm listening
                    updateState(VoiceSessionState.IDLE)
                    scheduleHandsFreeRearm(300L)
                } else {
                    updateState(VoiceSessionState.ERROR)
                    listener?.onError(speechState.message)
                    if (handsFreeMode) {
                        scheduleHandsFreeRearm(1500L)
                    }
                }
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
        rearmJob?.cancel()
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
        rearmJob?.cancel()
        if (!spokenResponsesEnabled) {
            onDone?.invoke()
            if (handsFreeMode && _state.value != VoiceSessionState.WAITING_FOR_APPROVAL) {
                scheduleHandsFreeRearm(200L)
            }
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
            if (handsFreeMode && _state.value != VoiceSessionState.WAITING_FOR_APPROVAL) {
                scheduleHandsFreeRearm(250L)
            }
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
        rearmJob?.cancel()
        speechManager.stopListening()
        voiceOutput.stop()
        updateState(VoiceSessionState.IDLE)
    }

    /**
     * Shuts down resources.
     */
    fun shutdown() {
        rearmJob?.cancel()
        handsFreeMode = false
        speechManager.destroyRecognizer()
        voiceOutput.shutdown()
        updateState(VoiceSessionState.IDLE)
    }
}
