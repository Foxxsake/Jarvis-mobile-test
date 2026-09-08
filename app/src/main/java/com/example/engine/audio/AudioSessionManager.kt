package com.example.engine.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Identifies the component currently capturing or requesting the audio input / microphone.
 * Only ONE component may own microphone capture at any time.
 */
enum class AudioSessionOwner {
    NONE,
    WAKE_WORD,
    SPEECH_TO_TEXT,
    SPEAKER_VERIFICATION
}

/**
 * Manages exclusive ownership of the audio recording / microphone resource.
 * Enforces explicit transitions so wake-word, speaker verification, and STT never compete.
 */
class AudioSessionManager {

    private val _currentOwner = MutableStateFlow(AudioSessionOwner.NONE)
    val currentOwner: StateFlow<AudioSessionOwner> = _currentOwner.asStateFlow()

    private val ownerRef = AtomicReference(AudioSessionOwner.NONE)

    /**
     * Attempts to acquire audio capture session for [requester].
     * Returns true if granted, false if rejected.
     */
    @Synchronized
    fun requestSession(requester: AudioSessionOwner): Boolean {
        if (requester == AudioSessionOwner.NONE) {
            releaseSession(ownerRef.get())
            return true
        }

        val current = ownerRef.get()
        if (current == requester) {
            return true
        }

        // Allowed transitions:
        // NONE -> any
        // WAKE_WORD -> SPEAKER_VERIFICATION, SPEECH_TO_TEXT, NONE
        // SPEAKER_VERIFICATION -> SPEECH_TO_TEXT, NONE
        // SPEECH_TO_TEXT -> NONE (or explicit preemption)
        val isAllowed = when (current) {
            AudioSessionOwner.NONE -> true
            AudioSessionOwner.WAKE_WORD -> requester == AudioSessionOwner.SPEAKER_VERIFICATION || requester == AudioSessionOwner.SPEECH_TO_TEXT
            AudioSessionOwner.SPEAKER_VERIFICATION -> requester == AudioSessionOwner.SPEECH_TO_TEXT
            AudioSessionOwner.SPEECH_TO_TEXT -> false
        }

        if (isAllowed) {
            ownerRef.set(requester)
            _currentOwner.value = requester
            return true
        }

        return false
    }

    /**
     * Releases audio session if held by [requester].
     */
    @Synchronized
    fun releaseSession(requester: AudioSessionOwner) {
        if (ownerRef.get() == requester) {
            ownerRef.set(AudioSessionOwner.NONE)
            _currentOwner.value = AudioSessionOwner.NONE
        }
    }

    /**
     * Force-resets the audio session to NONE.
     */
    @Synchronized
    fun resetSession() {
        ownerRef.set(AudioSessionOwner.NONE)
        _currentOwner.value = AudioSessionOwner.NONE
    }
}
