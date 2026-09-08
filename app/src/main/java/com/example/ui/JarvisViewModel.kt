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
import com.example.engine.ToolCommandMatcher
import com.example.engine.speech.SpeechManager
import com.example.engine.speech.SpeechState
import com.example.engine.voice.JarvisSpeechFormatter
import com.example.engine.voice.JarvisVoiceOutput
import com.example.engine.voice.VoiceOutputState
import com.example.engine.voice.VoiceSessionController
import com.example.engine.voice.VoiceSessionListener
import com.example.engine.voice.VoiceSessionState
import com.example.engine.voice.handsfree.HandsFreeVoiceService
import com.example.engine.voice.speaker.LocalSpeakerVerifier
import com.example.engine.voice.speaker.SpeakerEnrollmentState
import com.example.engine.voice.speaker.SpeakerVerifier
import com.example.engine.voice.wakeword.SherpaWakeWordEngine
import com.example.engine.voice.wakeword.WakeWordEngine
import com.example.engine.voice.wakeword.WakeWordEngineStatus
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
    val ambiguousAppCandidates: List<com.example.engine.Tool>? = null,
    val permissionRationaleNeeded: String? = null,
    val permissionPermanentlyDenied: String? = null,
    val wakeWordStatus: WakeWordEngineStatus = WakeWordEngineStatus.DISABLED,
    val speakerEnrollmentState: SpeakerEnrollmentState = SpeakerEnrollmentState.NOT_ENROLLED,
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
    val voiceOutput: JarvisVoiceOutput? = null,
    val wakeWordEngine: WakeWordEngine = SherpaWakeWordEngine(),
    val speakerVerifier: SpeakerVerifier = LocalSpeakerVerifier(),
    val termuxWorker: com.example.engine.termux.TermuxWorker = com.example.engine.termux.FakeTermuxWorker(),
    val workspaceRegistry: com.example.data.workspace.WorkspaceRegistry = com.example.data.workspace.LocalWorkspaceRegistry()
) : ViewModel() {

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    private val fallbackVoiceOutput: JarvisVoiceOutput = voiceOutput ?: object : JarvisVoiceOutput {
        private val _st = MutableStateFlow<VoiceOutputState>(VoiceOutputState.Idle)
        override val state: StateFlow<VoiceOutputState> = _st
        override fun isAvailable(): Boolean = false
        override fun speak(text: String, onDone: (() -> Unit)?) { onDone?.invoke() }
        override fun stop() {}
        override fun shutdown() {}
    }

    val voiceSessionController = VoiceSessionController(
        speechManager = speechManager,
        voiceOutput = fallbackVoiceOutput
    )

    private val parser = CommandParser(toolMatcher = ToolCommandMatcher { toolRegistry.tools.value })
    private val taskRouter = TaskRouter(toolRegistry)

    val localProcessingEnabled: StateFlow<Boolean> = settingsManager.localProcessingFlow.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    val spokenResponsesEnabled: StateFlow<Boolean> = settingsManager.spokenResponsesFlow.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    val handsFreeEnabled: StateFlow<Boolean> = settingsManager.handsFreeFlow.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    val activityLogs: StateFlow<List<ActivityLog>> = repository.allLogs.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    val tools: StateFlow<List<com.example.engine.Tool>> = toolRegistry.tools

    init {
        refreshTermuxStatus()
        refreshActiveWorkspace()

        // Sync spoken responses preference with session controller
        viewModelScope.launch {
            spokenResponsesEnabled.collectLatest { enabled ->
                voiceSessionController.setSpokenResponsesEnabled(enabled)
            }
        }

        // Sync voice output state to UI
        viewModelScope.launch {
            fallbackVoiceOutput.state.collectLatest { vState ->
                _uiState.value = _uiState.value.copy(
                    voiceOutputState = vState,
                    status = if (vState is VoiceOutputState.Speaking) "Speaking..." else _uiState.value.status
                )
            }
        }

        // Sync wake-word and speaker verification status
        viewModelScope.launch {
            wakeWordEngine.status.collectLatest { wwStatus ->
                _uiState.value = _uiState.value.copy(wakeWordStatus = wwStatus)
            }
        }
        viewModelScope.launch {
            speakerVerifier.enrollmentState.collectLatest { spkState ->
                _uiState.value = _uiState.value.copy(speakerEnrollmentState = spkState)
            }
        }

        // Listen for voice session state changes
        voiceSessionController.setListener(object : VoiceSessionListener {
            override fun onStateChanged(state: VoiceSessionState) {
                val statusText = when (state) {
                    VoiceSessionState.IDLE -> if (_uiState.value.status.startsWith("Error")) _uiState.value.status else "Ready"
                    VoiceSessionState.LISTENING -> "Listening..."
                    VoiceSessionState.TRANSCRIBING -> "Processing speech..."
                    VoiceSessionState.PROCESSING -> "Processing..."
                    VoiceSessionState.WAITING_FOR_APPROVAL -> "Waiting for approval"
                    VoiceSessionState.SPEAKING -> "Speaking..."
                    VoiceSessionState.ERROR -> _uiState.value.status
                }
                _uiState.value = _uiState.value.copy(
                    voiceSessionState = state,
                    isListening = (state == VoiceSessionState.LISTENING),
                    status = statusText
                )
            }

            override fun onSpeechRecognized(text: String) {
                _uiState.value = _uiState.value.copy(
                    lastRecognizedText = text,
                    speechEventId = _uiState.value.speechEventId + 1
                )
                submitCommand(text)
            }

            override fun onError(message: String) {
                _uiState.value = _uiState.value.copy(status = "Error: $message")
            }
        })

        // Forward raw speech state into voiceSessionController
        viewModelScope.launch {
            speechManager.speechState.collectLatest { state ->
                voiceSessionController.handleSpeechState(state)
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
            if (plan.actions.size == 1 && plan.actions[0].action == CommandAction.OPEN_APP && plan.actions[0].candidateTools != null && plan.actions[0].candidateTools!!.size > 1) {
                _uiState.value = _uiState.value.copy(
                    status = "Select app",
                    pendingApproval = plan,
                    ambiguousAppQuery = plan.actions[0].targetAppOrPerson,
                    ambiguousAppCandidates = plan.actions[0].candidateTools
                )
                return@launch
            }

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
                voiceSessionController.speakResponse("Contacts permission is required.")
            }
            is ContactResolutionResult.ProviderError -> {
                logActivity(plan.originalText, plan.actions.first(), "Contact lookup", ToolExecutionStatus.FAILED.name, resolution.message)
                _uiState.value = _uiState.value.copy(status = "Contacts provider error")
                voiceSessionController.speakResponse("Sorry, there was an error accessing contacts.")
            }
            is ContactResolutionResult.Ambiguous -> {
                _uiState.value = _uiState.value.copy(
                    status = "Select contact",
                    pendingApproval = plan,
                    ambiguousQuery = resolution.query,
                    ambiguousCandidates = resolution.candidates,
                    pendingMessageForDestination = resolution.message
                )
                voiceSessionController.speakResponse("Multiple matching contacts found. Please select on screen.")
            }
            is ContactResolutionResult.MultipleDestinations -> {
                _uiState.value = _uiState.value.copy(
                    status = "Select phone number/email",
                    pendingApproval = plan,
                    multipleDestinationsName = resolution.displayName,
                    multipleDestinations = resolution.destinations,
                    pendingMessageForDestination = resolution.message
                )
                voiceSessionController.speakResponse("Multiple numbers or addresses found. Please choose on screen.")
            }
            is ContactResolutionResult.NotFound -> {
                logActivity(plan.originalText, plan.actions.first(), "Contact lookup", ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED.name, "Contact not found.")
                _uiState.value = _uiState.value.copy(status = "Contact not found")
                voiceSessionController.speakResponse("I couldn't find that contact.")
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
                val promptText = JarvisSpeechFormatter.formatApprovalPrompt(plan, approvalDetail)
                voiceSessionController.onWaitingForApproval(promptText)
            }
            ContactResolutionResult.ResolutionRequired -> {
                logActivity(plan.originalText, plan.actions.first(), "Contact lookup", ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED.name, "Contact name or target missing.")
                _uiState.value = _uiState.value.copy(status = "Contact details missing")
                voiceSessionController.speakResponse("Contact name is missing.")
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
            ambiguousAppCandidates = null,
            ambiguousAppQuery = null,
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
        val spokenText = JarvisSpeechFormatter.formatExecutionResult(plan.originalText, result)
        voiceSessionController.speakResponse(spokenText)
    }

    private suspend fun executePlan(
        plan: CommandPlan,
        startIndex: Int = 0,
        resolvedContact: ContactResolutionResult.Resolved? = null
    ) {
        var currentPlan = plan
        for (i in startIndex until currentPlan.actions.size) {
            var action = currentPlan.actions[i]

            // Action-by-action approval check
            if (action.requiresApproval && action.state != ActionExecutionState.RUNNING) {
                if (action.proposal == null && action.action == CommandAction.TERMUX_COMMAND) {
                    _uiState.value = _uiState.value.copy(status = "Inspecting workspace configuration...")
                    val resolvedProposal = resolveCommandProposal(action)
                    if (resolvedProposal != null) {
                        action = action.copy(proposal = resolvedProposal)
                        val newActions = currentPlan.actions.toMutableList()
                        newActions[i] = action
                        currentPlan = currentPlan.copy(actions = newActions)
                    }
                }

                val proposal = action.proposal
                val details = buildString {
                    if (currentPlan.actions.size > 1) {
                        appendLine("Action ${i + 1} of ${currentPlan.actions.size}: ${action.action.name}")
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
                    pendingApproval = currentPlan,
                    pendingActionIndex = i,
                    planToApprove = details
                )
                val approvalPrompt = JarvisSpeechFormatter.formatApprovalPrompt(currentPlan, details)
                voiceSessionController.onWaitingForApproval(approvalPrompt)
                return // Pause execution until user approves or rejects this specific action
            }

            if (action.action == CommandAction.UNKNOWN) {
                logActivity(
                    currentPlan.originalText,
                    action,
                    "Action ${i + 1}",
                    ToolExecutionStatus.NOT_IMPLEMENTED.name,
                    "Command not recognized locally. Requires AI engine."
                )
                val unknownSpoken = JarvisSpeechFormatter.formatExecutionResult(
                    currentPlan.originalText,
                    com.example.engine.ToolExecutionResult(
                        ToolExecutionStatus.NOT_IMPLEMENTED,
                        "Command not recognized locally. Requires AI engine."
                    )
                )
                voiceSessionController.speakResponse(unknownSpoken)
                if (!currentPlan.continueOnFailure) {
                    skipRemainingActions(currentPlan, i + 1, "Unrecognized command")
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
                currentPlan.originalText,
                action,
                "Action ${i + 1}: ${action.action.name}",
                result.status.name,
                result.message
            )

            if (result.status == ToolExecutionStatus.AMBIGUOUS_APP) {
                _uiState.value = _uiState.value.copy(
                    status = "Select app",
                    pendingApproval = currentPlan,
                    pendingActionIndex = i,
                    ambiguousAppQuery = action.targetAppOrPerson,
                    ambiguousAppCandidates = result.candidateTools
                )
                val spoken = JarvisSpeechFormatter.formatExecutionResult(currentPlan.originalText, result)
                voiceSessionController.speakResponse(spoken)
                return
            }

            val isSuccess = result.status == ToolExecutionStatus.SUCCESS
            if (!isSuccess && !currentPlan.continueOnFailure) {
                skipRemainingActions(currentPlan, i + 1, "Previous action '${action.action.name}' failed (${result.status.name})")
                _uiState.value = _uiState.value.copy(status = "Plan stopped: Action ${i + 1} failed")
                val spoken = JarvisSpeechFormatter.formatExecutionResult(currentPlan.originalText, result)
                voiceSessionController.speakResponse(spoken)
                return
            }

            // If this is the last action or single action in the plan, speak result
            if (i == currentPlan.actions.size - 1) {
                val spoken = JarvisSpeechFormatter.formatExecutionResult(currentPlan.originalText, result)
                voiceSessionController.speakResponse(spoken)
            }
        }

        _uiState.value = _uiState.value.copy(status = "Ready", pendingApproval = null, planToApprove = null)
    }

    fun selectAppCandidate(tool: com.example.engine.Tool) {
        val pending = _uiState.value.pendingApproval ?: return
        val currentIndex = _uiState.value.pendingActionIndex
        if (currentIndex !in pending.actions.indices) return

        val currentAction = pending.actions[currentIndex]
        val requiresApproval = (tool.policy == com.example.data.AccessPolicy.ASK_EACH_TIME)
        val updatedAction = currentAction.copy(
            targetAppOrPerson = tool.name,
            requiresApproval = requiresApproval,
            candidateTools = null
        )
        val updatedActions = pending.actions.toMutableList()
        updatedActions[currentIndex] = updatedAction
        val updatedPlan = pending.copy(actions = updatedActions)

        _uiState.value = _uiState.value.copy(
            ambiguousAppCandidates = null,
            ambiguousAppQuery = null,
            status = if (requiresApproval) "Waiting for approval: ${tool.name}" else "Launching ${tool.name}...",
            planToApprove = if (requiresApproval) "Launch ${tool.name}\nAccess Policy: ASK_EACH_TIME" else null,
            pendingApproval = if (requiresApproval) updatedPlan else null
        )

        if (!requiresApproval) {
            viewModelScope.launch {
                executePlan(updatedPlan, startIndex = currentIndex)
            }
        }
    }

    private suspend fun resolveCommandProposal(action: PlannedAction): com.example.engine.CommandProposal? {
        if (action.action != CommandAction.TERMUX_COMMAND || action.proposal != null) {
            return action.proposal
        }
        val ws = workspaceRegistry.getActiveWorkspace() ?: return null
        val inspector = com.example.engine.project.TermuxWorkspaceInspector(termuxWorker)
        val inspection = inspector.inspectWorkspace(ws)
        
        if (action.rawArguments == "test") {
            val res = com.example.engine.project.ProjectDetector.detectTestCommand(
                inspection.topLevelFiles, 
                inspection.packageJsonContent, 
                inspection.requirementsOrPyprojectContent
            )
            val isDetected = res is com.example.engine.project.TestCommandResult.Detected
            return com.example.engine.CommandProposal(
                tool = "Termux",
                workspace = ws.displayName,
                command = if (isDetected) (res as com.example.engine.project.TestCommandResult.Detected).command else "echo 'No test command detected'",
                riskLevel = com.example.engine.termux.TermuxRiskLevel.MUTATING,
                reason = "Run test suite: " + if (isDetected) (res as com.example.engine.project.TestCommandResult.Detected).reason else (res as com.example.engine.project.TestCommandResult.NotDetected).explanation
            )
        } else if (action.rawArguments == "build") {
            val res = com.example.engine.project.ProjectDetector.detectBuildCommand(
                inspection.topLevelFiles, 
                inspection.packageJsonContent
            )
            val isDetected = res is com.example.engine.project.BuildCommandResult.Detected
            return com.example.engine.CommandProposal(
                tool = "Termux",
                workspace = ws.displayName,
                command = if (isDetected) (res as com.example.engine.project.BuildCommandResult.Detected).command else "echo 'No build command detected'",
                riskLevel = com.example.engine.termux.TermuxRiskLevel.MUTATING,
                reason = "Build project: " + if (isDetected) (res as com.example.engine.project.BuildCommandResult.Detected).reason else (res as com.example.engine.project.BuildCommandResult.NotDetected).explanation
            )
        }
        return null
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
            val tool = toolRegistry.tools.value.firstOrNull { it.id == toolId }
            if (tool != null) {
                val policy = if (enabled) com.example.data.AccessPolicy.ALLOW else com.example.data.AccessPolicy.BLOCK
                toolRegistry.setToolPolicy(tool, policy)
            }
            refreshTools()
        }
    }

    fun updateToolPolicy(tool: com.example.engine.Tool, policy: com.example.data.AccessPolicy) {
        viewModelScope.launch {
            toolRegistry.setToolPolicy(tool, policy)
            val enabled = (policy != com.example.data.AccessPolicy.BLOCK)
            settingsManager.setToolEnabled(tool.id, enabled)
            refreshTools()
        }
    }

    fun updateAppPolicy(packageName: String, policy: com.example.data.AccessPolicy) {
        viewModelScope.launch {
            toolRegistry.setAppPolicy(packageName, policy)
            refreshTools()
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
        voiceSessionController.shutdown()
    }
}
