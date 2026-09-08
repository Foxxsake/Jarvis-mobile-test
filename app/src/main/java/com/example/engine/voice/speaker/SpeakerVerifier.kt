package com.example.engine.voice.speaker

enum class SpeakerEnrollmentState {
    NOT_ENROLLED,
    ENROLLING,
    ENROLLED,
    ERROR
}

enum class SpeakerVerificationResult {
    VERIFIED,
    REJECTED,
    NOT_ENROLLED,
    TIMEOUT,
    ERROR
}

interface SpeakerVerifier {
    val enrollmentState: kotlinx.coroutines.flow.StateFlow<SpeakerEnrollmentState>
    fun isEnrolled(): Boolean
    suspend fun verify(audioData: ByteArray): SpeakerVerificationResult
    suspend fun startEnrollment()
    suspend fun cancelEnrollment()
}

/**
 * Local-first architecture for later Sherpa-ONNX speaker identification.
 * Preserves local on-device voiceprints/embeddings.
 * In Pass 5A, clearly leaves state as NOT_ENROLLED until model connection.
 */
class LocalSpeakerVerifier : SpeakerVerifier {

    private val _enrollmentState = kotlinx.coroutines.flow.MutableStateFlow(SpeakerEnrollmentState.NOT_ENROLLED)
    override val enrollmentState: kotlinx.coroutines.flow.StateFlow<SpeakerEnrollmentState> = _enrollmentState

    override fun isEnrolled(): Boolean = _enrollmentState.value == SpeakerEnrollmentState.ENROLLED

    override suspend fun verify(audioData: ByteArray): SpeakerVerificationResult {
        // Will evaluate local embeddings against stored voiceprint in Pass 5B
        return if (!isEnrolled()) {
            SpeakerVerificationResult.NOT_ENROLLED
        } else {
            SpeakerVerificationResult.REJECTED
        }
    }

    override suspend fun startEnrollment() {
        _enrollmentState.value = SpeakerEnrollmentState.ENROLLING
    }

    override suspend fun cancelEnrollment() {
        _enrollmentState.value = SpeakerEnrollmentState.NOT_ENROLLED
    }
}
