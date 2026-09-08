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
    speechManager: SpeechManager? = null,
    toolRegistry: ToolRegistry? = null,
    repository: ActivityRepository? = null,
    toolExecutor: ToolExecutor? = null,
    contactResolver: ContactResolver? = null,
    settingsManager: SettingsManager? = null,
    voiceOutput: JarvisVoiceOutput? = null,
    injectedVoiceSessionController: VoiceSessionController? = null,
    val wakeWordEngine: WakeWordEngine = SherpaWakeWordEngine(),
    val speakerVerifier: SpeakerVerifier = LocalSpeakerVerifier(),
    termuxWorker: TermuxWorker? = null,
    workspaceRegistry: WorkspaceRegistry? = null,
    val runtime: JarvisRuntime? = null
) : ViewModel() {

    val settingsManager: SettingsManager = settingsManager
        ?: runtime?.settingsManager
        ?: throw IllegalArgumentException("SettingsManager or JarvisRuntime is required")

    val toolRegistry: ToolRegistry = toolRegistry
        ?: runtime?.toolRegistry
        ?: throw IllegalArgumentException("ToolRegistry or JarvisRuntime is required")

    val repository: ActivityRepository = repository
        ?: runtime?.activityRepository
        ?: throw IllegalArgumentException("ActivityRepository or JarvisRuntime is required")

    val workspaceRegistry: WorkspaceRegistry = workspaceRegistry
        ?: runtime?.workspaceRegistry
        ?: com.example.data.workspace.LocalWorkspaceRegistry(this.settingsManager.context)

    val termuxWorker: TermuxWorker = termuxWorker
        ?: runtime?.termuxWorker
        ?: throw IllegalArgumentException("TermuxWorker or JarvisRuntime is required")

    private val fallbackToolExecutor: ToolExecutor? = toolExecutor

    val voiceSessionController: VoiceSessionController = injectedVoiceSessionController
        ?: runtime?.voiceSessionController
        ?: if (speechManager != null) {
            val vOutput = voiceOutput ?: object : JarvisVoiceOutput {
                private val _state = MutableStateFlow<VoiceOutputState>(VoiceOutputState.Idle)
                override val state: StateFlow<VoiceOutputState> = _state.asStateFlow()
                override fun isAvailable(): Boolean = true
                override fun speak(text: String, onDone: (() -> Unit)?) { onDone?.invoke() }
                override fun stop() {}
                override fun shutdown() {}
            }
            VoiceSessionController(speechManager, vOutput, AudioSessionManager())
        } else {
            throw IllegalArgumentException("VoiceSessionController, SpeechManager or JarvisRuntime is required")
        }

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
        if (runtime != null) {
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
        } else {
            if (speechManager != null) {
                viewModelScope.launch {
                    speechManager.speechState.collectLatest { speechState ->
                        voiceSessionController.handleSpeechState(speechState)
                    }
                }
            }
            voiceSessionController.addListener(object : VoiceSessionListener {
                override fun onStateChanged(state: VoiceSessionState) {}
                override fun onSpeechRecognized(text: String) {
                    submitCommand(text)
                }
                override fun onError(message: String) {}
            })
        }
    }

    fun submitCommand(text: String) {
        if (text.isBlank()) return
        if (runtime != null) {
            runtime.executeCommand(text)
        } else {
            _uiState.value = _uiState.value.copy(
                lastRecognizedText = text,
                speechEventId = System.currentTimeMillis()
            )
            viewModelScope.launch {
                val parser = com.example.engine.CommandParser(toolMatcher = com.example.engine.ToolCommandMatcher { toolRegistry.tools.value })
                val plan = parser.parse(text)
                if (plan.actions.isNotEmpty()) {
                    val act = plan.actions.first()
                    fallbackToolExecutor?.executeAction(act, null, isLocalProcessingEnabled = true)
                }
            }
        }
    }

    fun startListening() {
        voiceSessionController.startListening()
    }

    fun stopListening() {
        voiceSessionController.stopListening()
    }

    fun approvePending() {
        runtime?.approvePending()
    }

    fun rejectPending() {
        runtime?.rejectPending()
    }

    fun selectContactCandidate(candidate: ContactCandidate) {
        runtime?.selectContactCandidate(candidate)
    }

    fun selectContactDestination(destination: ContactDestination) {
        runtime?.selectContactDestination(destination)
    }

    fun selectAppCandidate(tool: Tool) {
        runtime?.selectAppCandidate(tool)
    }

    fun dismissPermissionRationale() {
        runtime?.dismissPermissionRationale()
            ?: run { _uiState.value = _uiState.value.copy(permissionRationaleNeeded = null) }
    }

    fun dismissPermissionPermanentlyDenied() {
        runtime?.dismissPermissionPermanentlyDenied()
            ?: run { _uiState.value = _uiState.value.copy(permissionPermanentlyDenied = null) }
    }

    fun showPermissionRationale(permType: String) {
        runtime?.showPermissionRationale(permType)
            ?: run { _uiState.value = _uiState.value.copy(permissionRationaleNeeded = permType) }
    }

    fun showPermissionPermanentlyDenied(permType: String) {
        runtime?.showPermissionPermanentlyDenied(permType)
            ?: run { _uiState.value = _uiState.value.copy(permissionPermanentlyDenied = permType) }
    }

    fun refreshActiveWorkspace() {
        runtime?.refreshActiveWorkspace()
    }

    fun setWorkspacePath(displayName: String, path: String) {
        runtime?.setWorkspacePath(displayName, path)
    }

    fun refreshTermuxStatus() {
        runtime?.refreshTermuxStatus() ?: viewModelScope.launch {
            val status = termuxWorker.checkConnectionState()
            _uiState.value = _uiState.value.copy(termuxStatus = status)
        }
    }

    fun refreshTools() {
        toolRegistry.refreshTools()
    }

    suspend fun probeTermuxConnection(): TermuxConnectionStatus {
        val result = termuxWorker.probeConnection()
        _uiState.value = _uiState.value.copy(termuxStatus = result)
        runtime?.refreshTermuxStatus()
        return result
    }

    fun toggleToolEnabled(toolId: String, enabled: Boolean) {
        runtime?.toggleToolEnabled(toolId, enabled)
    }

    fun updateToolPolicy(tool: Tool, policy: AccessPolicy) {
        runtime?.updateToolPolicy(tool, policy)
    }

    fun updateAppPolicy(packageName: String, policy: AccessPolicy) {
        runtime?.updateAppPolicy(packageName, policy)
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
