package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ActivityLog
import com.example.data.ActivityRepository
import com.example.data.SettingsManager
import com.example.engine.CommandAction
import com.example.engine.CommandParser
import com.example.engine.ContactResolver
import com.example.engine.ParsedCommand
import com.example.engine.TaskRouter
import com.example.engine.ToolExecutionStatus
import com.example.engine.ToolExecutor
import com.example.engine.ToolRegistry
import com.example.engine.contacts.ContactCandidate
import com.example.engine.contacts.ContactDestination
import com.example.engine.contacts.ContactResolutionResult
import com.example.engine.speech.SpeechManager
import com.example.engine.speech.SpeechState
import com.example.util.PrivacyUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class JarvisUiState(
    val status: String = "Ready",
    val pendingApproval: ParsedCommand? = null,
    val planToApprove: String? = null,
    val resolvedContact: ContactResolutionResult.Resolved? = null,
    val ambiguousCandidates: List<ContactCandidate>? = null,
    val ambiguousQuery: String? = null,
    val multipleDestinations: List<ContactDestination>? = null,
    val multipleDestinationsName: String? = null,
    val pendingMessageForDestination: String? = null,
    val permissionRationaleNeeded: String? = null, // "MIC" or "CONTACTS"
    val permissionPermanentlyDenied: String? = null, // "MIC" or "CONTACTS"
    val lastRecognizedText: String = ""
)

class JarvisViewModel(
    private val repository: ActivityRepository,
    val settingsManager: SettingsManager,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val contactResolver: ContactResolver,
    val speechManager: SpeechManager,
    private val commandParser: CommandParser = CommandParser(),
    private val taskRouter: TaskRouter = TaskRouter(toolRegistry)
) : ViewModel() {

    val activityLogs: StateFlow<List<ActivityLog>> = repository.allLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val tools = toolRegistry.tools

    val localProcessingEnabled: StateFlow<Boolean> = settingsManager.localProcessingFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsManager.disabledToolIdsFlow.collect { disabledSet ->
                toolRegistry.updateDisabledTools(disabledSet)
            }
        }

        viewModelScope.launch {
            speechManager.speechState.collect { speechState ->
                when (speechState) {
                    is SpeechState.Ready -> {
                        if (_uiState.value.status == "Listening" || _uiState.value.status == "Processing speech") {
                            _uiState.value = _uiState.value.copy(status = "Ready")
                        }
                    }
                    is SpeechState.Listening -> {
                        _uiState.value = _uiState.value.copy(status = "Listening")
                    }
                    is SpeechState.Processing -> {
                        _uiState.value = _uiState.value.copy(status = "Processing speech")
                    }
                    is SpeechState.Success -> {
                        _uiState.value = _uiState.value.copy(
                            status = "Ready",
                            lastRecognizedText = speechState.text
                        )
                        processCommand(speechState.text)
                    }
                    is SpeechState.Error -> {
                        _uiState.value = _uiState.value.copy(status = speechState.message)
                    }
                    is SpeechState.PermissionRequired -> {
                        _uiState.value = _uiState.value.copy(
                            status = "Microphone permission required",
                            permissionRationaleNeeded = "MIC"
                        )
                    }
                    is SpeechState.Unavailable -> {
                        _uiState.value = _uiState.value.copy(status = "Speech unavailable")
                    }
                }
            }
        }
    }

    fun processCommand(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            val isLocalEnabled = localProcessingEnabled.value
            val parsed = commandParser.parse(text)

            if (!isLocalEnabled) {
                _uiState.value = _uiState.value.copy(status = "Local processing disabled")
                logActivity(
                    parsed,
                    "Local processing disabled",
                    ToolExecutionStatus.FAILED.name,
                    "Local command processing is currently disabled in settings."
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                status = "Planning",
                ambiguousCandidates = null,
                ambiguousQuery = null,
                multipleDestinations = null,
                multipleDestinationsName = null,
                permissionRationaleNeeded = null,
                permissionPermanentlyDenied = null
            )

            if (parsed.action == CommandAction.CALL ||
                parsed.action == CommandAction.TEXT ||
                parsed.action == CommandAction.EMAIL
            ) {
                val resolution = contactResolver.resolveCommandTarget(parsed)
                handleContactResolution(parsed, resolution)
                return@launch
            }

            val plan = taskRouter.route(parsed)

            if (parsed.requiresApproval) {
                _uiState.value = _uiState.value.copy(
                    status = "Waiting for approval",
                    pendingApproval = parsed,
                    planToApprove = plan.steps.joinToString("\n")
                )
            } else {
                execute(parsed, plan.steps.joinToString(", "))
            }
        }
    }

    fun handleContactResolution(parsed: ParsedCommand, resolution: ContactResolutionResult) {
        when (resolution) {
            is ContactResolutionResult.PermissionRequired -> {
                _uiState.value = _uiState.value.copy(
                    status = "Contacts permission required",
                    permissionRationaleNeeded = "CONTACTS",
                    pendingApproval = parsed
                )
            }
            is ContactResolutionResult.ProviderError -> {
                logActivity(
                    parsed,
                    "Contact lookup",
                    ToolExecutionStatus.FAILED.name,
                    resolution.message
                )
                _uiState.value = _uiState.value.copy(status = "Contacts provider error")
            }
            is ContactResolutionResult.Ambiguous -> {
                _uiState.value = _uiState.value.copy(
                    status = "Select contact",
                    pendingApproval = parsed,
                    ambiguousQuery = resolution.query,
                    ambiguousCandidates = resolution.candidates,
                    pendingMessageForDestination = resolution.message
                )
            }
            is ContactResolutionResult.MultipleDestinations -> {
                _uiState.value = _uiState.value.copy(
                    status = "Select phone number/email",
                    pendingApproval = parsed,
                    multipleDestinationsName = resolution.displayName,
                    multipleDestinations = resolution.destinations,
                    pendingMessageForDestination = resolution.message
                )
            }
            is ContactResolutionResult.NotFound -> {
                logActivity(
                    parsed,
                    "Contact lookup",
                    ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED.name,
                    "Contact not found."
                )
                _uiState.value = _uiState.value.copy(status = "Contact not found")
            }
            is ContactResolutionResult.Resolved -> {
                val plan = taskRouter.route(parsed)
                val maskedDest = if (parsed.action == CommandAction.EMAIL) {
                    resolution.destination.value
                } else {
                    PrivacyUtils.maskPhoneNumber(resolution.destination.value)
                }
                val approvalDetail = "${parsed.action.name} ${resolution.displayName} ($maskedDest)\nPlan: ${plan.steps.firstOrNull() ?: ""}"

                _uiState.value = _uiState.value.copy(
                    status = "Waiting for approval",
                    pendingApproval = parsed,
                    planToApprove = approvalDetail,
                    resolvedContact = resolution
                )
            }
            ContactResolutionResult.ResolutionRequired -> {
                logActivity(
                    parsed,
                    "Contact lookup",
                    ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED.name,
                    "Contact name or target missing."
                )
                _uiState.value = _uiState.value.copy(status = "Contact details missing")
            }
        }
    }

    fun selectContactCandidate(candidate: ContactCandidate) {
        val pending = _uiState.value.pendingApproval ?: return
        val msg = _uiState.value.pendingMessageForDestination
        val resolution = contactResolver.resolveCandidateDestinations(candidate, msg)

        _uiState.value = _uiState.value.copy(
            ambiguousCandidates = null,
            ambiguousQuery = null
        )

        handleContactResolution(pending, resolution)
    }

    fun selectContactDestination(destination: ContactDestination) {
        val pending = _uiState.value.pendingApproval ?: return
        val name = _uiState.value.multipleDestinationsName ?: "Contact"
        val msg = _uiState.value.pendingMessageForDestination

        val resolved = ContactResolutionResult.Resolved(
            displayName = name,
            destination = destination,
            message = msg
        )

        _uiState.value = _uiState.value.copy(
            multipleDestinations = null,
            multipleDestinationsName = null
        )

        handleContactResolution(pending, resolved)
    }

    fun approvePending() {
        val pending = _uiState.value.pendingApproval
        val planText = _uiState.value.planToApprove
        val resolved = _uiState.value.resolvedContact

        if (pending != null) {
            viewModelScope.launch {
                execute(pending, planText ?: "", resolved)
            }
        }
        clearApproval()
    }

    fun rejectPending() {
        val pending = _uiState.value.pendingApproval
        if (pending != null) {
            logActivity(
                pending,
                "User rejected action",
                "REJECTED",
                "User rejected proposed command action."
            )
        }
        clearApproval()
    }

    fun dismissPermissionRationale() {
        _uiState.value = _uiState.value.copy(permissionRationaleNeeded = null)
    }

    fun showPermissionRationale(permType: String) {
        _uiState.value = _uiState.value.copy(permissionRationaleNeeded = permType)
    }

    fun showPermissionPermanentlyDenied(permType: String) {
        _uiState.value = _uiState.value.copy(permissionPermanentlyDenied = permType)
    }

    fun dismissPermissionPermanentlyDenied() {
        _uiState.value = _uiState.value.copy(permissionPermanentlyDenied = null)
    }

    private fun clearApproval() {
        _uiState.value = _uiState.value.copy(
            status = "Ready",
            pendingApproval = null,
            planToApprove = null,
            resolvedContact = null,
            ambiguousCandidates = null,
            ambiguousQuery = null,
            multipleDestinations = null,
            multipleDestinationsName = null,
            permissionRationaleNeeded = null
        )
    }

    private suspend fun execute(
        command: ParsedCommand,
        planText: String,
        resolvedContact: ContactResolutionResult.Resolved? = null
    ) {
        if (command.action == CommandAction.UNKNOWN) {
            logActivity(
                command,
                planText,
                ToolExecutionStatus.NOT_IMPLEMENTED.name,
                "Command not recognized locally. Requires AI engine."
            )
            _uiState.value = _uiState.value.copy(status = "Ready")
            return
        }

        val result = toolExecutor.executeAction(command, resolvedContact, localProcessingEnabled.value)
        logActivity(command, planText, result.status.name, result.message)
        _uiState.value = _uiState.value.copy(status = "Ready")
    }

    fun toggleToolEnabled(toolId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setToolEnabled(toolId, enabled)
        }
    }

    private fun logActivity(command: ParsedCommand, planText: String, status: String, resultMessage: String) {
        viewModelScope.launch {
            repository.insertLog(
                ActivityLog(
                    command = command.rawText,
                    classification = command.category.name,
                    proposedTool = planText.take(50),
                    status = status,
                    result = resultMessage,
                    approvalRequired = command.requiresApproval
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroyRecognizer()
    }
}
