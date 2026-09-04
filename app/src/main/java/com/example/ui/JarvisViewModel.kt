package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ActivityLog
import com.example.data.ActivityRepository
import com.example.data.SettingsManager
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
    val planToApprove: String? = null,
    val resolvedContact: ContactResolutionResult.Resolved? = null,
    val ambiguousQuery: String? = null,
    val ambiguousCandidates: List<ContactCandidate>? = null,
    val multipleDestinationsName: String? = null,
    val multipleDestinations: List<ContactDestination>? = null,
    val pendingMessageForDestination: String? = null,
    val permissionRationaleNeeded: String? = null,
    val permissionPermanentlyDenied: String? = null
)

class JarvisViewModel(
    private val speechManager: SpeechManager,
    private val toolRegistry: ToolRegistry,
    private val repository: ActivityRepository,
    private val toolExecutor: ToolExecutor,
    private val contactResolver: ContactResolver,
    val settingsManager: SettingsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    private val parser = CommandParser()
    private val taskRouter = TaskRouter(toolRegistry)

    val localProcessingEnabled: StateFlow<Boolean> = settingsManager.localProcessingFlow.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    val activityLogs: StateFlow<List<ActivityLog>> = repository.allLogs.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    val tools: StateFlow<List<com.example.engine.Tool>> = toolRegistry.tools

    init {
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

            val executionPlan = taskRouter.route(plan)
            
            if (plan.requiresApproval) {
                _uiState.value = _uiState.value.copy(
                    status = "Waiting for approval",
                    pendingApproval = plan,
                    planToApprove = executionPlan.steps.joinToString("\n")
                )
            } else {
                execute(plan, executionPlan.steps.joinToString("\n"))
            }
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
            for (action in pending.actions) {
                logActivity(pending.originalText, action, "User rejected action", "REJECTED", "User rejected proposed command action.")
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
        plan: CommandPlan,
        planText: String,
        resolvedContact: ContactResolutionResult.Resolved? = null
    ) {
        val lines = planText.split("\n")
        for ((index, action) in plan.actions.withIndex()) {
            val actionDesc = if (plan.actions.size == 1) planText else lines.getOrNull(index) ?: "Action ${index + 1}"
            
            if (action.action == CommandAction.UNKNOWN) {
                logActivity(
                    plan.originalText,
                    action,
                    actionDesc.take(50),
                    ToolExecutionStatus.NOT_IMPLEMENTED.name,
                    "Command not recognized locally. Requires AI engine."
                )
                continue
            }

            val result = toolExecutor.executeAction(action, resolvedContact, localProcessingEnabled.value)
            logActivity(
                plan.originalText,
                action,
                actionDesc.take(50),
                result.status.name,
                result.message
            )
        }
        _uiState.value = _uiState.value.copy(status = "Ready")
    }

    fun toggleToolEnabled(toolId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setToolEnabled(toolId, enabled)
        }
    }
    
    fun refreshTools() {
        toolRegistry.refreshTools()
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
