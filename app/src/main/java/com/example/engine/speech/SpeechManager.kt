package com.example.engine.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeechRecognizerBackend {
    ON_DEVICE,
    SYSTEM
}

data class SpeechDiagnostics(
    val backend: SpeechRecognizerBackend = SpeechRecognizerBackend.ON_DEVICE,
    val lastErrorCode: Int? = null,
    val lastErrorName: String? = null
)

sealed class SpeechState {
    object Ready : SpeechState()
    object Listening : SpeechState()
    object Processing : SpeechState()
    data class Success(val text: String) : SpeechState()
    data class Error(val message: String, val isTransient: Boolean = false) : SpeechState()
    object PermissionRequired : SpeechState()
    object Unavailable : SpeechState()
}

class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechManager"

        fun getSpeechErrorName(code: Int): String {
            return when (code) {
                SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                else -> "ERROR_UNKNOWN ($code)"
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _speechState = MutableStateFlow<SpeechState>(SpeechState.Ready)
    val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    private var preferredBackend: SpeechRecognizerBackend = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    ) {
        SpeechRecognizerBackend.ON_DEVICE
    } else {
        SpeechRecognizerBackend.SYSTEM
    }

    private val _diagnostics = MutableStateFlow(SpeechDiagnostics(backend = preferredBackend))
    val diagnostics: StateFlow<SpeechDiagnostics> = _diagnostics.asStateFlow()

    private var currentSessionId: Long = 0L
    private var speechRecognizer: SpeechRecognizer? = null
    private var consecutiveOnDeviceFailures = 0

    val currentSessionToken: Long
        get() = currentSessionId

    fun verifySessionToken(token: Long): Boolean = (token == currentSessionId)

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening() {
        currentSessionId++
        val sessionId = currentSessionId
        mainHandler.post {
            if (!isAvailable()) {
                Log.w(TAG, "Speech recognition is unavailable on device")
                _speechState.value = SpeechState.Unavailable
                return@post
            }

            destroyRecognizerInternal()

            var targetBackend = preferredBackend
            Log.d(TAG, "Starting speech session #$sessionId with backend=$targetBackend")

            _diagnostics.value = _diagnostics.value.copy(backend = targetBackend)

            var recognizer: SpeechRecognizer? = null
            try {
                recognizer = createRecognizerForBackend(targetBackend)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create recognizer for $targetBackend: ${e.message}", e)
                if (targetBackend == SpeechRecognizerBackend.ON_DEVICE) {
                    Log.i(TAG, "Falling back immediately to SYSTEM recognizer due to creation failure")
                    preferredBackend = SpeechRecognizerBackend.SYSTEM
                    targetBackend = SpeechRecognizerBackend.SYSTEM
                    _diagnostics.value = _diagnostics.value.copy(backend = targetBackend)
                    try {
                        recognizer = createRecognizerForBackend(targetBackend)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to create fallback SYSTEM recognizer: ${e2.message}", e2)
                        _speechState.value = SpeechState.Error("Failed to initialize speech input: ${e2.message}")
                        return@post
                    }
                } else {
                    _speechState.value = SpeechState.Error("Failed to initialize speech input: ${e.message}")
                    return@post
                }
            }

            speechRecognizer = recognizer
            var isTerminal = false

            val activeRecognizer = recognizer
            val activeBackend = targetBackend

            activeRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (sessionId != currentSessionId || isTerminal) return
                    _speechState.value = SpeechState.Listening
                }

                override fun onBeginningOfSpeech() {
                    if (sessionId != currentSessionId || isTerminal) return
                    _speechState.value = SpeechState.Listening
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    if (sessionId != currentSessionId || isTerminal) return
                    _speechState.value = SpeechState.Processing
                }

                override fun onError(error: Int) {
                    if (sessionId != currentSessionId || isTerminal) return
                    isTerminal = true

                    val errorName = getSpeechErrorName(error)
                    Log.w(TAG, "Session #$sessionId error: $error ($errorName) on backend=$activeBackend")

                    _diagnostics.value = SpeechDiagnostics(
                        backend = activeBackend,
                        lastErrorCode = error,
                        lastErrorName = errorName
                    )

                    if (activeBackend == SpeechRecognizerBackend.ON_DEVICE) {
                        consecutiveOnDeviceFailures++
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == SpeechRecognizer.ERROR_SERVER ||
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            consecutiveOnDeviceFailures >= 2
                        ) {
                            Log.i(TAG, "Falling back to SYSTEM speech recognizer due to $errorName (failures=$consecutiveOnDeviceFailures)")
                            preferredBackend = SpeechRecognizerBackend.SYSTEM
                        }
                    }

                    val isTransient = (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)

                    val msg = mapSpeechError(error)
                    _speechState.value = SpeechState.Error(msg, isTransient = isTransient)

                    safelyDestroyRecognizer(activeRecognizer)
                    if (speechRecognizer == activeRecognizer) {
                        speechRecognizer = null
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (sessionId != currentSessionId || isTerminal) return
                    isTerminal = true

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull { !it.isNullOrBlank() }?.trim()

                    Log.d(TAG, "Session #$sessionId results candidates=$matches chosen='$text'")

                    if (!text.isNullOrBlank()) {
                        if (activeBackend == SpeechRecognizerBackend.ON_DEVICE) {
                            consecutiveOnDeviceFailures = 0
                        }
                        _speechState.value = SpeechState.Success(text)
                    } else {
                        val errorName = "ERROR_NO_MATCH"
                        _diagnostics.value = SpeechDiagnostics(
                            backend = activeBackend,
                            lastErrorCode = SpeechRecognizer.ERROR_NO_MATCH,
                            lastErrorName = errorName
                        )
                        _speechState.value = SpeechState.Error("No speech matched. Please try again.", isTransient = true)
                    }

                    safelyDestroyRecognizer(activeRecognizer)
                    if (speechRecognizer == activeRecognizer) {
                        speechRecognizer = null
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-GB")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }

            try {
                activeRecognizer.startListening(intent)
                _speechState.value = SpeechState.Listening
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening on session #$sessionId: ${e.message}", e)
                if (sessionId == currentSessionId && !isTerminal) {
                    isTerminal = true
                    if (activeBackend == SpeechRecognizerBackend.ON_DEVICE) {
                        preferredBackend = SpeechRecognizerBackend.SYSTEM
                    }
                    _speechState.value = SpeechState.Error("Failed to start speech input: ${e.message}")
                    safelyDestroyRecognizer(activeRecognizer)
                    if (speechRecognizer == activeRecognizer) {
                        speechRecognizer = null
                    }
                }
            }
        }
    }

    private fun createRecognizerForBackend(backend: SpeechRecognizerBackend): SpeechRecognizer {
        return if (backend == SpeechRecognizerBackend.ON_DEVICE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    fun stopListening() {
        currentSessionId++
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            destroyRecognizerInternal()
            _speechState.value = SpeechState.Ready
        }
    }

    fun resetState() {
        currentSessionId++
        mainHandler.post {
            destroyRecognizerInternal()
            _speechState.value = SpeechState.Ready
        }
    }

    fun destroyRecognizer() {
        currentSessionId++
        mainHandler.post {
            destroyRecognizerInternal()
            _speechState.value = SpeechState.Ready
        }
    }

    private fun destroyRecognizerInternal() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun safelyDestroyRecognizer(recognizer: SpeechRecognizer?) {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}
    }

    private fun mapSpeechError(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech matched. Please try again."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network connection error during speech recognition."
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timed out."
            SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error."
            else -> "Speech recognition error."
        }
    }
}
