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

enum class VoiceSessionMode {
    IDLE,
    PUSH_TO_TALK,
    HANDS_FREE
}

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
 * Coordinates Push-To-Talk and Hands-Free continuous listening mode with clean serialized states.
 */
class VoiceSessionController(
    private val speechManager: com.example.engine.speech.SpeechManager,
    private val voiceOutput: JarvisVoiceOutput
) {

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rearmJob: Job? = null

    private val _state = MutableStateFlow(VoiceSessionState.IDLE)
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private val _sessionMode = MutableStateFlow(VoiceSessionMode.IDLE)
    val sessionMode: StateFlow<VoiceSessionMode> = _sessionMode.asStateFlow()

    private var spokenResponsesEnabled = true
    private var handsFreeMode = false
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<VoiceSessionListener>()

    fun setListener(listener: VoiceSessionListener?) {
        listeners.clear()
        if (listener != null) {
            listeners.add(listener)
        }
    }

    fun addListener(listener: VoiceSessionListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: VoiceSessionListener) {
        listeners.remove(listener)
    }

    fun setSpokenResponsesEnabled(enabled: Boolean) {
        this.spokenResponsesEnabled = enabled
    }

    fun isSpokenResponsesEnabled(): Boolean = spokenResponsesEnabled

    fun setHandsFreeMode(enabled: Boolean) {
        this.handsFreeMode = enabled
        rearmJob?.cancel()
        if (enabled) {
            if (_state.value == VoiceSessionState.IDLE && _sessionMode.value != VoiceSessionMode.PUSH_TO_TALK) {
                _sessionMode.value = VoiceSessionMode.HANDS_FREE
                scheduleHandsFreeRearm(50L)
            }
        } else {
            if (_sessionMode.value == VoiceSessionMode.HANDS_FREE) {
                _sessionMode.value = VoiceSessionMode.IDLE
                speechManager.stopListening()
                if (_state.value == VoiceSessionState.LISTENING || _state.value == VoiceSessionState.TRANSCRIBING) {
                    updateState(VoiceSessionState.IDLE)
                }
            }
        }
    }

    fun isHandsFreeMode(): Boolean = handsFreeMode

    /**
     * Explicit Push-To-Talk invocation from user action (e.g. mic button).
     * Stops any ongoing TTS, pauses hands-free rearm, and initiates a single push-to-talk listening session.
     */
    fun startPushToTalk() {
        rearmJob?.cancel()
        voiceOutput.stop()
        _sessionMode.value = VoiceSessionMode.PUSH_TO_TALK
        updateState(VoiceSessionState.LISTENING)
        speechManager.startListening()
    }

    /**
     * Internal hands-free listening trigger.
     */
    private fun startHandsFreeListening() {
        if (!handsFreeMode) return
        if (_sessionMode.value == VoiceSessionMode.PUSH_TO_TALK) return
        if (_state.value == VoiceSessionState.WAITING_FOR_APPROVAL) return
        if (_state.value == VoiceSessionState.SPEAKING) return

        _sessionMode.value = VoiceSessionMode.HANDS_FREE
        updateState(VoiceSessionState.LISTENING)
        speechManager.startListening()
    }

    /**
     * User or trigger requested to start listening.
     */
    fun startListening() {
        if (handsFreeMode) {
            startHandsFreeListening()
        } else {
            startPushToTalk()
        }
    }

    /**
     * Stops listening immediately.
     */
    fun stopListening() {
        rearmJob?.cancel()
        speechManager.stopListening()
        if (_state.value == VoiceSessionState.LISTENING || _state.value == VoiceSessionState.TRANSCRIBING) {
            updateState(VoiceSessionState.IDLE)
        }
        if (_sessionMode.value == VoiceSessionMode.PUSH_TO_TALK) {
            _sessionMode.value = if (handsFreeMode) VoiceSessionMode.HANDS_FREE else VoiceSessionMode.IDLE
        }
    }

    /**
     * Schedule re-arming of the microphone for continuous hands-free operation.
     */
    private fun scheduleHandsFreeRearm(delayMs: Long = 350L) {
        if (!handsFreeMode) return
        if (_state.value == VoiceSessionState.WAITING_FOR_APPROVAL) return
        rearmJob?.cancel()
        rearmJob = controllerScope.launch {
            delay(delayMs)
            if (handsFreeMode && _state.value == VoiceSessionState.IDLE && _state.value != VoiceSessionState.WAITING_FOR_APPROVAL) {
                startHandsFreeListening()
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
                    if (handsFreeMode && _sessionMode.value == VoiceSessionMode.HANDS_FREE) {
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
                listeners.forEach { it.onSpeechRecognized(speechState.text) }
            }
            is com.example.engine.speech.SpeechState.Error -> {
                if (_sessionMode.value == VoiceSessionMode.HANDS_FREE && speechState.isTransient) {
                    // Normal silence or timeout in continuous hands-free mode:
                    // Treat as a quiet listening cycle without flashing error on the UI.
                    updateState(VoiceSessionState.IDLE)
                    scheduleHandsFreeRearm(350L)
                } else {
                    updateState(VoiceSessionState.ERROR)
                    listeners.forEach { it.onError(speechState.message) }
                    if (handsFreeMode) {
                        _sessionMode.value = VoiceSessionMode.HANDS_FREE
                        scheduleHandsFreeRearm(1500L)
                    } else {
                        _sessionMode.value = VoiceSessionMode.IDLE
                    }
                }
            }
            is com.example.engine.speech.SpeechState.PermissionRequired -> {
                updateState(VoiceSessionState.ERROR)
                listeners.forEach { it.onError("Microphone permission required") }
                _sessionMode.value = VoiceSessionMode.IDLE
            }
            is com.example.engine.speech.SpeechState.Unavailable -> {
                updateState(VoiceSessionState.ERROR)
                listeners.forEach { it.onError("Speech recognition unavailable") }
                _sessionMode.value = VoiceSessionMode.IDLE
            }
        }
    }

    /**
     * Called when a command requires user approval.
     */
    fun onWaitingForApproval(promptText: String? = null) {
        rearmJob?.cancel()
        _sessionMode.value = VoiceSessionMode.IDLE
        updateState(VoiceSessionState.WAITING_FOR_APPROVAL)
        if (spokenResponsesEnabled && !promptText.isNullOrBlank()) {
            speakResponse(promptText) {
                // Stay in WAITING_FOR_APPROVAL after speech completes
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
                _sessionMode.value = VoiceSessionMode.HANDS_FREE
                scheduleHandsFreeRearm(200L)
            } else {
                _sessionMode.value = VoiceSessionMode.IDLE
                if (_state.value == VoiceSessionState.SPEAKING || _state.value == VoiceSessionState.PROCESSING) {
                    updateState(VoiceSessionState.IDLE)
                }
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
                _sessionMode.value = VoiceSessionMode.HANDS_FREE
                scheduleHandsFreeRearm(300L)
            } else {
                _sessionMode.value = VoiceSessionMode.IDLE
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
        _sessionMode.value = VoiceSessionMode.IDLE
        updateState(VoiceSessionState.IDLE)
    }

    /**
     * Shuts down resources.
     */
    fun shutdown() {
        rearmJob?.cancel()
        handsFreeMode = false
        _sessionMode.value = VoiceSessionMode.IDLE
        speechManager.destroyRecognizer()
        voiceOutput.shutdown()
        updateState(VoiceSessionState.IDLE)
    }

    private fun updateState(newState: VoiceSessionState) {
        _state.value = newState
        listeners.forEach { it.onStateChanged(newState) }
    }
}
