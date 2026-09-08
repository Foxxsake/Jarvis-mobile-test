package com.example.engine.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

class AndroidVoiceOutput(
    private val context: Context
) : JarvisVoiceOutput {

    private val _state = MutableStateFlow<VoiceOutputState>(VoiceOutputState.Initializing)
    override val state: StateFlow<VoiceOutputState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentUtteranceId: String? = null
    private var completionCallback: (() -> Unit)? = null

    init {
        initializeTts()
    }

    private fun initializeTts() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val ttsInstance = tts
                    if (ttsInstance != null) {
                        configureTts(ttsInstance)
                        isInitialized = true
                        _state.value = VoiceOutputState.Ready
                    } else {
                        _state.value = VoiceOutputState.Unavailable
                    }
                } else {
                    _state.value = VoiceOutputState.Error("Text-to-speech initialization failed (status $status)")
                }
            }
        } catch (e: Exception) {
            _state.value = VoiceOutputState.Error("Failed to create TextToSpeech: ${e.message}")
        }
    }

    private fun configureTts(tts: TextToSpeech) {
        // Preferred locale: en-GB
        val targetLocale = Locale.UK
        val langResult = tts.setLanguage(targetLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fallback to default Locale if en-GB is missing
            tts.setLanguage(Locale.ENGLISH)
        }

        // Try selecting a suitable British English voice if available
        try {
            val availableVoices = tts.voices
            if (!availableVoices.isNullOrEmpty()) {
                val britishVoice = availableVoices.firstOrNull { voice ->
                    voice.locale == Locale.UK && !voice.isNetworkConnectionRequired
                } ?: availableVoices.firstOrNull { voice ->
                    voice.locale == Locale.UK
                } ?: availableVoices.firstOrNull { voice ->
                    voice.locale.language == "en"
                }

                if (britishVoice != null) {
                    tts.voice = britishVoice
                }
            }
        } catch (_: Exception) {
            // Some older devices or engines might throw on voices access
        }

        tts.setPitch(1.0f)
        tts.setSpeechRate(1.0f)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == currentUtteranceId) {
                    // Already in Speaking state
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == currentUtteranceId) {
                    _state.value = VoiceOutputState.Ready
                    val cb = completionCallback
                    completionCallback = null
                    currentUtteranceId = null
                    cb?.invoke()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == currentUtteranceId) {
                    _state.value = VoiceOutputState.Error("Speech output error")
                    val cb = completionCallback
                    completionCallback = null
                    currentUtteranceId = null
                    cb?.invoke()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == currentUtteranceId) {
                    _state.value = VoiceOutputState.Error("Speech output error code: $errorCode")
                    val cb = completionCallback
                    completionCallback = null
                    currentUtteranceId = null
                    cb?.invoke()
                }
            }
        })
    }

    override fun isAvailable(): Boolean {
        return isInitialized && tts != null
    }

    override fun speak(text: String, onDone: (() -> Unit)?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onDone?.invoke()
            return
        }

        val currentTts = tts
        if (!isInitialized || currentTts == null) {
            _state.value = VoiceOutputState.Error("Text-to-speech not ready")
            onDone?.invoke()
            return
        }

        // Prevent overlapping speech: stop previous utterance immediately
        stop()

        val utteranceId = UUID.randomUUID().toString()
        currentUtteranceId = utteranceId
        completionCallback = onDone
        _state.value = VoiceOutputState.Speaking(trimmed)

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val result = currentTts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            _state.value = VoiceOutputState.Error("Failed to queue spoken response")
            completionCallback = null
            currentUtteranceId = null
            onDone?.invoke()
        }
    }

    override fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
        currentUtteranceId = null
        val cb = completionCallback
        completionCallback = null
        if (_state.value is VoiceOutputState.Speaking) {
            _state.value = VoiceOutputState.Ready
        }
        cb?.invoke()
    }

    override fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isInitialized = false
        _state.value = VoiceOutputState.Idle
    }
}
