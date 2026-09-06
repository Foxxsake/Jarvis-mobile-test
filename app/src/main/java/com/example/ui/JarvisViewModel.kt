package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ActivityLog
import com.example.data.ActivityRepository
import com.example.data.SettingsManager
import com.example.engine.ActionExecutionState
import com.example.engine.CommandAction
import com.example.engine.CommandPlan
import com.example.engine.CommandParser
import com.example.engine.contacts.ContactCandidate
import com.example.engine.contacts.ContactDestination
import com.example.engine.contacts.ContactResolutionResult
import com.example.engine.ContactResolver
import com.example.engine.PlannedAction
import com.example.engine.TaskRouter
import com.example.engine.ToolExecutionStatus
import com.example.engine.ToolExecutor
import com.example.engine.ToolRegistry
import com.example.engine.speech.SpeechManager
import com.example.engine.speech.SpeechState
import com.example.util.PrivacyUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class JarvisUiState(
    val status: String = "Ready",
    val isListening: Boolean = false,
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
    val permissionRationaleNeeded: String? = null,
    val permissionPermanentlyDenied: String? = null,
    val termuxStatus: com.example.engine.termux.TermuxConnectionStatus = com.example.engine.termux.TermuxConnectionStatus(
        isInstalled = false,
        isPermissionGranted = false,
        isExternalAppsAllowed = false,
        connectionState = com.example.engine.termux.TermuxConnectionState.TERMUX_NOT_INSTALLED
    ),
    val activeWorkspace: com.example.data.workspace.Workspace? = null,
    val lastTermuxResult: com.example.engine.termux.TermuxExecutionResult? = null
)

class JarvisViewModel(
    private val speechManager: SpeechManager,
    private val toolRegistry: ToolRegistry,
    private val repository: ActivityRepository,
    private val toolExecutor: ToolExecutor,
    private val contactResolver: ContactResolver,
    val settingsManager: SettingsManager,
    val termuxWorker: com.example.engine.termux.TermuxWorker = com.example.engine.termux.FakeTermuxWorker(),
    val workspaceRegistry: com.example.data.workspace.WorkspaceRegistry = com.example.data.workspace.LocalWorkspaceRegistry()
) : ViewModel() {

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    private val parser = CommandParser()
    private val taskRouter = TaskRouter(toolRegistry)

    val localProcessingEnabled: StateFlow<Boolean> = settingsManager.localProcessingFlow.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    val activityLogs: StateFlow<List<ActivityLog>> = repository.allLogs.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    val tools: StateFlow<List<com.example.engine.Tool>> = toolRegistry.tools

    init {
        refreshTermuxStatus()
        refreshActiveWorkspace()
        viewModelScope.launch {
            speechManager.speechState.collectLatest { state ->
                when (state) {
                    is SpeechState.Ready -> _uiState.value = _uiState.value.copy(isListening = false)
                    is SpeechState.Listening -> _uiState.value = _uiState.value.copy(isListening = true, status = "Listening...")
                    is SpeechState.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isListening = false,
                            lastRecognizedText = state.text,
                            speechEventId = _uiState.value.speechEventId + 1,
                            status = "Ready"
                        )
                        submitCommand(state.text)
                    }
                    is SpeechState.Error -> { 
                        _uiState.value = _uiState.value.copy( 
                            isListening = false, 
                            status = "Error: ${state.message}" 
                        ) 
                    } 
                    is SpeechState.PermissionRequired -> { 
                        _uiState.value = _uiState.value.copy( 
                            isListening = false, 
                            status = "Microphone permission required" 
                        ) 
                    } 
                    is SpeechState.Unavailable -> { 
                        _uiState.value = _uiState.value.copy( 
                            isListening = false, 
                            status = "Speech recognition unavailable" 
                        ) 
                    } 
                    is SpeechState.Processing -> { 
                        _uiState.value = _uiState.value.copy( 
                            isListening = false, 
                            status = "Processing speech..." 
                        ) 
                    }
                }
            }
        }
    }

    fun startListening() {
        speechManager.startListening()
    }

    fun submitCommand(text: String) {
        if (text.isBlank()) return
        
        val plan = parser.parse(text)
        
        viewModelScope.launch {
            if (plan.actions.size == 1 && (plan.actions[0].action == CommandAction.CALL || plan.actions[0].action == CommandAction.TEXT || plan.actions[0].action == CommandAction.EMAIL)) {
                val resolution = contactResolver.resolveCommandTarget(plan.actions[0])
                handleContactResolution(plan, resolution)
                return@launch
            }

            executePlan(plan, startIndex = 0)
        }
    }

    fun handleContactResolution(plan: CommandPlan, resolution: ContactResolutionResult) {
        when (resolution) {
            is ContactResolutionResult.PermissionRequired -> {
                _uiState.value = _uiState.value.copy(
                    status = "Contacts permission required",
                    permissionRationaleNeeded = "CONTACTS",
                    pendingApproval = plan
                )
            }
            is ContactResolutionResult.ProviderError -> {
                logActivity(plan.originalText, plan.actions.first(), "Contact lookup", ToolExecutionStatus.FAILED.name, resolution.message)
                _uiState.value = _uiState.value.copy(status = "Contacts provider error")
            }
            is ContactResolutionResult.Ambiguous -> {
                _uiState.value = _uiState.value.copy(
                    status = "Select contact",
                    pendingApproval = plan,
                    ambiguousQuery = resolution.query,
                    ambiguousCandidates = resolution.candidates,
                    pendingMessageForDestination = resolution.message
                )
            }
            is ContactResolutionResult.MultipleDestinations -> {
                _uiState.value = _uiState.value.copy(
                    status = "Select phone number/email",
                    pendingApproval = plan,
                    multipleDestinationsName = resolution.displayName,
                    multipleDestinations = resolution.destinations,
                    pendingMessageForDestination = resolution.message
                )
            }
            is ContactResolutionResult.NotFound -> {
                logActivity(plan.originalText, plan.actions.first(), "Contact lookup", ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED.name, "Contact not found.")
                _uiState.value = _uiState.value.copy(status = "Contact not found")
            }
            is ContactResolutionResult.Resolved -> {
                val executionPlan = taskRouter.route(plan)
                val action = plan.actions.first()
                val maskedDest = if (action.action == CommandAction.EMAIL) {
                    resolution.destination.value
                } else {
                    PrivacyUtils.maskPhoneNumber(resolution.destination.value)
                }
                val approvalDetail = "${action.action.name} ${resolution.displayName} ($maskedDest)\nPlan: ${executionPlan.steps.firstOrNull() ?: ""}"
                
                _uiState.value = _uiState.value.copy(
                    status = "Waiting for approval",
                    pendingApproval = plan,
                    planToApprove = approvalDetail,
                    resolvedContact = resolution
                )
            }
            ContactResolutionResult.ResolutionRequired -> {
                logActivity(plan.originalText, plan.actions.first(), "Contact lookup", ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED.name, "Contact name or target missing.")
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
        val pending = _uiState.value.pendingApproval ?: return
        val currentIndex = _uiState.value.pendingActionIndex
        val resolved = _uiState.value.resolvedContact

        // Contact flow approval (calls/texts/emails)
        if (resolved != null && pending.actions.size == 1) {
            viewModelScope.launch {
                executeSingle(pending, _uiState.value.planToApprove ?: "", resolved)
            }
            clearApproval()
            return
        }

        // Action-by-action approval for plans
        val updatedActions = pending.actions.toMutableList()
        if (currentIndex in updatedActions.indices) {
            updatedActions[currentIndex] = updatedActions[currentIndex].copy(
                state = ActionExecutionState.RUNNING,
                requiresApproval = false
            )
        }
        val updatedPlan = pending.copy(actions = updatedActions)

        _uiState.value = _uiState.value.copy(
            pendingApproval = null,
            planToApprove = null,
            status = "Approved, executing action ${currentIndex + 1}..."
        )

        viewModelScope.launch {
            executePlan(updatedPlan, startIndex = currentIndex, resolvedContact = resolved)
        }
    }

    fun rejectPending() {
        val pending = _uiState.value.pendingApproval
        val currentIndex = _uiState.value.pendingActionIndex
        if (pending != null) {
            if (currentIndex in pending.actions.indices) {
                val rejectedAction = pending.actions[currentIndex].copy(state = ActionExecutionState.REJECTED)
                logActivity(
                    pending.originalText,
                    rejectedAction,
                    "Action ${currentIndex + 1}: ${rejectedAction.action.name}",
                    "REJECTED",
                    "User rejected proposed command action."
                )
                skipRemainingActions(pending, currentIndex + 1, "Action ${currentIndex + 1} was rejected by user")
            } else {
                for (action in pending.actions) {
                    logActivity(pending.originalText, action, "User rejected action", "REJECTED", "User rejected proposed command action.")
                }
            }
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
            pendingActionIndex = 0,
            planToApprove = null,
            resolvedContact = null,
            ambiguousCandidates = null,
            ambiguousQuery = null,
            multipleDestinations = null,
            multipleDestinationsName = null,
            permissionRationaleNeeded = null
        )
    }

    private suspend fun executeSingle(
        plan: CommandPlan,
        planText: String,
        resolvedContact: ContactResolutionResult.Resolved?
    ) {
        val action = plan.actions.first()
        val result = toolExecutor.executeAction(action, resolvedContact, localProcessingEnabled.value)
        logActivity(
            plan.originalText,
            action,
            planText.take(50),
            result.status.name,
            result.message
        )
        _uiState.value = _uiState.value.copy(status = "Ready")
    }

    private suspend fun executePlan(
        plan: CommandPlan,
        startIndex: Int = 0,
        resolvedContact: ContactResolutionResult.Resolved? = null
    ) {
        for (i in startIndex until plan.actions.size) {
            val action = plan.actions[i]

            // Action-by-action approval check
            if (action.requiresApproval && action.state != ActionExecutionState.RUNNING) {
                val proposal = action.proposal
                val details = buildString {
                    if (plan.actions.size > 1) {
                        appendLine("Action ${i + 1} of ${plan.actions.size}: ${action.action.name}")
                    }
                    if (proposal != null) {
                        appendLine("Command: ${proposal.command}")
                        appendLine("Tool: ${proposal.tool}")
                        val ws = workspaceRegistry.getActiveWorkspace()?.displayName ?: proposal.workspace
                        appendLine("Workspace: $ws")
                        appendLine("Risk: ${proposal.riskLevel.name}")
                        appendLine("Reason: ${proposal.reason}")
                    } else if (!action.rawArguments.isNullOrBlank()) {
                        appendLine("Arguments: ${action.rawArguments}")
                    }
                }.trim()

                _uiState.value = _uiState.value.copy(
                    status = "Waiting for approval: ${action.action.name}",
                    pendingApproval = plan,
                    pendingActionIndex = i,
                    planToApprove = details
                )
                return // Pause execution until user approves or rejects this specific action
            }

            if (action.action == CommandAction.UNKNOWN) {
                logActivity(
                    plan.originalText,
                    action,
                    "Action ${i + 1}",
                    ToolExecutionStatus.NOT_IMPLEMENTED.name,
                    "Command not recognized locally. Requires AI engine."
                )
                if (!plan.continueOnFailure) {
                    skipRemainingActions(plan, i + 1, "Unrecognized command")
                    _uiState.value = _uiState.value.copy(status = "Plan stopped: Unrecognized command")
                    return
                }
                continue
            }

            _uiState.value = _uiState.value.copy(
                status = "Executing ${action.action.name}..."
            )

            val result = toolExecutor.executeAction(action, resolvedContact, localProcessingEnabled.value)

            logActivity(
                plan.originalText,
                action,
                "Action ${i + 1}: ${action.action.name}",
                result.status.name,
                result.message
            )

            val isSuccess = result.status == ToolExecutionStatus.SUCCESS
            if (!isSuccess && !plan.continueOnFailure) {
                skipRemainingActions(plan, i + 1, "Previous action '${action.action.name}' failed (${result.status.name})")
                _uiState.value = _uiState.value.copy(status = "Plan stopped: Action ${i + 1} failed")
                return
            }
        }

        _uiState.value = _uiState.value.copy(status = "Ready", pendingApproval = null, planToApprove = null)
    }

    private fun skipRemainingActions(plan: CommandPlan, fromIndex: Int, reason: String) {
        for (j in fromIndex until plan.actions.size) {
            val skippedAction = plan.actions[j].copy(state = ActionExecutionState.SKIPPED)
            logActivity(
                plan.originalText,
                skippedAction,
                "Action ${j + 1}: ${skippedAction.action.name}",
                ToolExecutionStatus.SKIPPED.name,
                "Skipped because previous action failed: $reason"
            )
        }
    }

    fun toggleToolEnabled(toolId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setToolEnabled(toolId, enabled)
        }
    }
    
    fun refreshTools() {
        toolRegistry.refreshTools()
    }

    fun refreshTermuxStatus() {
        viewModelScope.launch {
            val probedStatus = termuxWorker.probeConnection()
            _uiState.value = _uiState.value.copy(
                termuxStatus = probedStatus
            )
        }
    }


    fun refreshActiveWorkspace() {
        _uiState.value = _uiState.value.copy(
            activeWorkspace = workspaceRegistry.getActiveWorkspace()
        )
    }

    fun setWorkspacePath(displayName: String, path: String) {
        val ws = com.example.data.workspace.Workspace(
            id = java.util.UUID.randomUUID().toString(),
            displayName = displayName,
            localPath = path
        )
        workspaceRegistry.setActiveWorkspace(ws)
        refreshActiveWorkspace()
    }

    private fun logActivity(originalText: String, action: PlannedAction, planText: String, status: String, resultMessage: String) {
        viewModelScope.launch {
            repository.insertLog(
                ActivityLog(
                    command = originalText, // Keep the overall command string
                    classification = action.category.name,
                    proposedTool = planText,
                    status = status,
                    result = resultMessage,
                    approvalRequired = action.requiresApproval
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroyRecognizer()
    }
}
