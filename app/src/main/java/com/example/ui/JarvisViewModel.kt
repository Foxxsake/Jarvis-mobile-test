package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AccessPolicy
import com.example.data.ActivityLog
import com.example.data.ActivityRepository
import com.example.data.SettingsManager
import com.example.data.workspace.Workspace
import com.example.data.workspace.WorkspaceRegistry
import com.example.engine.CommandPlan
import com.example.engine.ContactResolver
import com.example.engine.JarvisRuntime
import com.example.engine.Tool
import com.example.engine.ToolExecutor
import com.example.engine.ToolRegistry
import com.example.engine.audio.AudioSessionManager
import com.example.engine.audio.AudioSessionOwner
import com.example.engine.contacts.ContactCandidate
import com.example.engine.contacts.ContactDestination
import com.example.engine.contacts.ContactResolutionResult
import com.example.engine.speech.SpeechManager
import com.example.engine.termux.TermuxConnectionState
import com.example.engine.termux.TermuxConnectionStatus
import com.example.engine.termux.TermuxExecutionResult
import com.example.engine.termux.TermuxWorker
import com.example.engine.voice.JarvisVoiceOutput
import com.example.engine.voice.VoiceOutputState
import com.example.engine.voice.VoiceSessionController
import com.example.engine.voice.VoiceSessionListener
import com.example.engine.voice.VoiceSessionState
import com.example.engine.voice.handsfree.HandsFreeState
import com.example.engine.voice.handsfree.HandsFreeVoiceService
import com.example.engine.voice.speaker.LocalSpeakerVerifier
import com.example.engine.voice.speaker.SpeakerEnrollmentState
import com.example.engine.voice.speaker.SpeakerVerifier
import com.example.engine.voice.wakeword.SherpaWakeWordEngine
import com.example.engine.voice.wakeword.WakeWordEngine
import com.example.engine.voice.wakeword.WakeWordEngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class JarvisUiState(
    val status: String = "Ready",
    val isListening: Boolean = false,
    val voiceSessionState: VoiceSessionState = VoiceSessionState.IDLE,
    val voiceOutputState: VoiceOutputState = VoiceOutputState.Idle,
    val lastRecognizedText: String = "",
    val speechEventId: Long = 0L,
    val pendingApproval: CommandPlan? = null,
    val pendingActionIndex: Int = 0,
    val planToApprove: String? = null,
    val resolvedContact: ContactResolutionResult.Resolved? = null,
    val ambiguousQuery: String? = null,
    val ambiguousCandidates: List<ContactCandidate>? = null,
    val multipleDestinationsName: String? = null,
    val multipleDestinations: List<ContactDestination>? = null,
    val pendingMessageForDestination: String? = null,
    val ambiguousAppQuery: String? = null,
    val ambiguousAppCandidates: List<Tool>? = null,
    val permissionRationaleNeeded: String? = null,
    val permissionPermanentlyDenied: String? = null,
    val wakeWordStatus: WakeWordEngineStatus = WakeWordEngineStatus.DISABLED,
    val speakerEnrollmentState: SpeakerEnrollmentState = SpeakerEnrollmentState.NOT_ENROLLED,
    val termuxStatus: TermuxConnectionStatus = TermuxConnectionStatus(
        isInstalled = false,
        isPermissionGranted = false,
        isExternalAppsAllowed = false,
        connectionState = TermuxConnectionState.UNVERIFIED
    ),
    val activeWorkspace: Workspace? = null,
    val lastTermuxResult: TermuxExecutionResult? = null
)

/**
 * UI Adapter ViewModel for the JARVIS Immersive UI.
 * Connects Compose screens to the unified, application-scoped [JarvisRuntime].
 * Does NOT own or duplicate command parsing, routing, or execution logic.
 */
class JarvisViewModel(
    val runtime: JarvisRuntime,
    val wakeWordEngine: WakeWordEngine = SherpaWakeWordEngine(),
    val speakerVerifier: SpeakerVerifier = LocalSpeakerVerifier()
) : ViewModel() {

    val settingsManager: SettingsManager = runtime.settingsManager
    val toolRegistry: ToolRegistry = runtime.toolRegistry
    val repository: ActivityRepository = runtime.activityRepository
    val workspaceRegistry: WorkspaceRegistry = runtime.workspaceRegistry
    val termuxWorker: TermuxWorker = runtime.termuxWorker
    val toolExecutor: ToolExecutor = runtime.toolExecutor
    val voiceSessionController: VoiceSessionController = runtime.voiceSessionController

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    val localProcessingEnabled: StateFlow<Boolean> = this.settingsManager.localProcessingFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val spokenResponsesEnabled: StateFlow<Boolean> = this.settingsManager.spokenResponsesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val handsFreeEnabled: StateFlow<Boolean> = this.settingsManager.handsFreeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val handsFreeServiceState: StateFlow<HandsFreeState> = HandsFreeVoiceService.serviceState

    val activityLogs: StateFlow<List<ActivityLog>> = this.repository.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tools: StateFlow<List<Tool>> = this.toolRegistry.tools

    private val uiVoiceListener = object : VoiceSessionListener {
        override fun onStateChanged(state: VoiceSessionState) {
            _uiState.value = _uiState.value.copy(
                voiceSessionState = state,
                isListening = (state == VoiceSessionState.LISTENING || state == VoiceSessionState.TRANSCRIBING)
            )
        }

        override fun onSpeechRecognized(text: String) {
            _uiState.value = _uiState.value.copy(
                lastRecognizedText = text,
                speechEventId = System.currentTimeMillis()
            )
        }

        override fun onError(message: String) {
            _uiState.value = _uiState.value.copy(
                status = "Voice Error: $message"
            )
        }
    }

    init {
        // Register UI listener with controller without clearing existing runtime listeners
        voiceSessionController.addListener(uiVoiceListener)

        // Sync UI state from JarvisRuntime
        viewModelScope.launch {
            runtime.executionState.collectLatest { rtState ->
                _uiState.value = _uiState.value.copy(
                    status = rtState.status,
                    pendingApproval = rtState.pendingApproval,
                    pendingActionIndex = rtState.pendingActionIndex,
                    planToApprove = rtState.planToApprove,
                    resolvedContact = rtState.resolvedContact,
                    ambiguousQuery = rtState.ambiguousQuery,
                    ambiguousCandidates = rtState.ambiguousCandidates,
                    multipleDestinationsName = rtState.multipleDestinationsName,
                    multipleDestinations = rtState.multipleDestinations,
                    pendingMessageForDestination = rtState.pendingMessageForDestination,
                    ambiguousAppQuery = rtState.ambiguousAppQuery,
                    ambiguousAppCandidates = rtState.ambiguousAppCandidates,
                    permissionRationaleNeeded = rtState.permissionRationaleNeeded,
                    permissionPermanentlyDenied = rtState.permissionPermanentlyDenied,
                    lastRecognizedText = if (rtState.lastRecognizedText.isNotBlank()) rtState.lastRecognizedText else _uiState.value.lastRecognizedText,
                    speechEventId = if (rtState.speechEventId > 0) rtState.speechEventId else _uiState.value.speechEventId,
                    termuxStatus = rtState.termuxStatus,
                    activeWorkspace = rtState.activeWorkspace,
                    lastTermuxResult = rtState.lastTermuxResult
                )
            }
        }
    }

    fun submitCommand(text: String) {
        if (text.isBlank()) return
        runtime.executeCommand(text)
    }

    fun startListening() {
        voiceSessionController.startListening()
    }

    fun stopListening() {
        voiceSessionController.stopListening()
    }

    fun approvePending() {
        runtime.approvePending()
    }

    fun rejectPending() {
        runtime.rejectPending()
    }

    fun selectContactCandidate(candidate: ContactCandidate) {
        runtime.selectContactCandidate(candidate)
    }

    fun selectContactDestination(destination: ContactDestination) {
        runtime.selectContactDestination(destination)
    }

    fun selectAppCandidate(tool: Tool) {
        runtime.selectAppCandidate(tool)
    }

    fun dismissPermissionRationale() {
        runtime.dismissPermissionRationale()
    }

    fun dismissPermissionPermanentlyDenied() {
        runtime.dismissPermissionPermanentlyDenied()
    }

    fun showPermissionRationale(permType: String) {
        runtime.showPermissionRationale(permType)
    }

    fun showPermissionPermanentlyDenied(permType: String) {
        runtime.showPermissionPermanentlyDenied(permType)
    }

    fun refreshActiveWorkspace() {
        runtime.refreshActiveWorkspace()
    }

    fun setWorkspacePath(displayName: String, path: String) {
        runtime.setWorkspacePath(displayName, path)
    }

    fun refreshTermuxStatus() {
        runtime.refreshTermuxStatus()
    }

    fun refreshTools() {
        toolRegistry.refreshTools()
    }

    suspend fun probeTermuxConnection(): TermuxConnectionStatus {
        val result = termuxWorker.probeConnection()
        _uiState.value = _uiState.value.copy(termuxStatus = result)
        runtime.refreshTermuxStatus()
        return result
    }

    fun toggleToolEnabled(toolId: String, enabled: Boolean) {
        runtime.toggleToolEnabled(toolId, enabled)
    }

    fun updateToolPolicy(tool: Tool, policy: AccessPolicy) {
        runtime.updateToolPolicy(tool, policy)
    }

    fun updateAppPolicy(packageName: String, policy: AccessPolicy) {
        runtime.updateAppPolicy(packageName, policy)
    }

    fun startHandsFree(context: Context) {
        HandsFreeVoiceService.startService(context)
    }

    fun stopHandsFree(context: Context) {
        HandsFreeVoiceService.stopService(context)
    }

    override fun onCleared() {
        super.onCleared()
        // Remove only the UI listener; NEVER destroy shared runtime voice/speech subsystems
        voiceSessionController.removeListener(uiVoiceListener)
    }
}
