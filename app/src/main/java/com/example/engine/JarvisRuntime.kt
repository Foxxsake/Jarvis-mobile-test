package com.example.engine

import android.content.Context
import com.example.data.ActivityRepository
import com.example.data.AppDatabase
import com.example.data.SettingsManager
import com.example.data.workspace.LocalWorkspaceRegistry
import com.example.data.workspace.WorkspaceRegistry
import com.example.engine.contacts.AndroidContactsProvider
import com.example.engine.contacts.ContactResolutionResult
import com.example.engine.speech.SpeechManager
import com.example.engine.termux.AndroidTermuxWorker
import com.example.engine.termux.TermuxWorker
import com.example.engine.voice.AndroidVoiceOutput
import com.example.engine.voice.JarvisSpeechFormatter
import com.example.engine.voice.JarvisVoiceOutput
import com.example.engine.voice.VoiceSessionController
import com.example.engine.voice.VoiceSessionListener
import com.example.engine.voice.VoiceSessionState
import com.example.util.PrivacyUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

data class RuntimeExecutionState(
    val status: String = "Ready",
    val pendingApproval: CommandPlan? = null,
    val pendingActionIndex: Int = 0,
    val planToApprove: String? = null,
    val resolvedContact: ContactResolutionResult.Resolved? = null,
    val permissionRationaleNeeded: String? = null,
    val lastRecognizedText: String = ""
)

/**
 * Application-scoped runtime coordinator for JARVIS.
 * Provides the single source of truth for:
 * - Command parsing and execution
 * - ToolRegistry and App Policies
 * - Safety & Approval management
 * - Contacts & Termux coordination
 * - Speech recognition and Voice output lifecycle
 */
class JarvisRuntime private constructor(val context: Context) {

    val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val database: AppDatabase = AppDatabase.getDatabase(context)
    val activityRepository: ActivityRepository = ActivityRepository(database.activityLogDao())
    val settingsManager: SettingsManager = SettingsManager(context)
    val toolRegistry: ToolRegistry = ToolRegistry(context, database.appPolicyDao())
    val contactsProvider: AndroidContactsProvider = AndroidContactsProvider(context)
    val contactResolver: ContactResolver = ContactResolver(contactsProvider)
    val termuxWorker: TermuxWorker = AndroidTermuxWorker(context)
    val workspaceRegistry: WorkspaceRegistry = LocalWorkspaceRegistry(context)

    val toolExecutor: ToolExecutor = ToolExecutor(
        context = context,
        toolRegistry = toolRegistry,
        contactResolver = contactResolver,
        termuxWorker = termuxWorker,
        workspaceRegistry = workspaceRegistry
    )

    val speechManager: SpeechManager = SpeechManager(context)
    val voiceOutput: JarvisVoiceOutput = AndroidVoiceOutput(context)

    val voiceSessionController: VoiceSessionController = VoiceSessionController(
        speechManager = speechManager,
        voiceOutput = voiceOutput
    )

    val parser: CommandParser = CommandParser(toolMatcher = ToolCommandMatcher { toolRegistry.tools.value })
    val taskRouter: TaskRouter = TaskRouter(toolRegistry)

    private val _executionState = MutableStateFlow(RuntimeExecutionState())
    val executionState: StateFlow<RuntimeExecutionState> = _executionState.asStateFlow()

    init {
        // Collect spoken responses setting
        runtimeScope.launch {
            settingsManager.spokenResponsesFlow.collectLatest { enabled ->
                voiceSessionController.setSpokenResponsesEnabled(enabled)
            }
        }

        // Bridge speechManager to voiceSessionController
        runtimeScope.launch {
            speechManager.speechState.collectLatest { speechState ->
                voiceSessionController.handleSpeechState(speechState)
            }
        }

        // Default listener on voiceSessionController to execute recognized commands
        voiceSessionController.setListener(object : VoiceSessionListener {
            override fun onStateChanged(state: VoiceSessionState) {
                val statusText = when (state) {
                    VoiceSessionState.IDLE -> "Ready"
                    VoiceSessionState.LISTENING -> "Listening..."
                    VoiceSessionState.TRANSCRIBING -> "Processing speech..."
                    VoiceSessionState.PROCESSING -> "Processing..."
                    VoiceSessionState.WAITING_FOR_APPROVAL -> "Waiting for approval"
                    VoiceSessionState.SPEAKING -> "Speaking..."
                    VoiceSessionState.ERROR -> "Error"
                }
                _executionState.value = _executionState.value.copy(status = statusText)
            }

            override fun onSpeechRecognized(text: String) {
                _executionState.value = _executionState.value.copy(lastRecognizedText = text)
                executeCommand(text)
            }

            override fun onError(message: String) {
                _executionState.value = _executionState.value.copy(status = "Error: $message")
            }
        })
    }

    fun executeCommand(text: String) {
        if (text.isBlank()) return
        val plan = parser.parse(text)

        runtimeScope.launch {
            val isLocalOnly = settingsManager.localProcessingFlow.firstOrNull() ?: true

            // 1. Ambiguous App
            if (plan.actions.size == 1 && plan.actions[0].action == CommandAction.OPEN_APP &&
                plan.actions[0].candidateTools != null && plan.actions[0].candidateTools!!.size > 1
            ) {
                _executionState.value = _executionState.value.copy(
                    status = "Select app",
                    pendingApproval = plan
                )
                voiceSessionController.speakResponse("Multiple apps matched. Please select on screen.")
                return@launch
            }

            // 2. Contact resolution
            if (plan.actions.size == 1 && (plan.actions[0].action == CommandAction.CALL ||
                        plan.actions[0].action == CommandAction.TEXT ||
                        plan.actions[0].action == CommandAction.EMAIL)
            ) {
                val resolution = contactResolver.resolveCommandTarget(plan.actions[0])
                handleContactResolution(plan, resolution)
                return@launch
            }

            // 3. Sequential plan execution
            executePlanInternal(plan, startIndex = 0, isLocalOnly = isLocalOnly)
        }
    }

    private suspend fun handleContactResolution(plan: CommandPlan, resolution: ContactResolutionResult) {
        when (resolution) {
            is ContactResolutionResult.PermissionRequired -> {
                _executionState.value = _executionState.value.copy(
                    status = "Contacts permission required",
                    permissionRationaleNeeded = "CONTACTS",
                    pendingApproval = plan
                )
                voiceSessionController.speakResponse("Contacts permission is required.")
            }
            is ContactResolutionResult.ProviderError -> {
                logActivity(plan.originalText, plan.actions.first(), "Contact lookup", ToolExecutionStatus.FAILED.name, resolution.message)
                _executionState.value = _executionState.value.copy(status = "Contacts provider error")
                voiceSessionController.speakResponse("Sorry, there was an error accessing contacts.")
            }
            is ContactResolutionResult.Ambiguous -> {
                _executionState.value = _executionState.value.copy(
                    status = "Select contact",
                    pendingApproval = plan
                )
                voiceSessionController.speakResponse("Multiple matching contacts found. Please select on screen.")
            }
            is ContactResolutionResult.MultipleDestinations -> {
                _executionState.value = _executionState.value.copy(
                    status = "Select phone number/email",
                    pendingApproval = plan
                )
                voiceSessionController.speakResponse("Multiple numbers or addresses found. Please choose on screen.")
            }
            is ContactResolutionResult.NotFound -> {
                logActivity(plan.originalText, plan.actions.first(), "Contact lookup", ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED.name, "Contact not found.")
                _executionState.value = _executionState.value.copy(status = "Contact not found")
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

                _executionState.value = _executionState.value.copy(
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
                _executionState.value = _executionState.value.copy(status = "Contact details missing")
                voiceSessionController.speakResponse("Contact name is missing.")
            }
        }
    }

    private suspend fun executePlanInternal(
        plan: CommandPlan,
        startIndex: Int = 0,
        resolvedContact: ContactResolutionResult.Resolved? = null,
        isLocalOnly: Boolean = true
    ) {
        var currentPlan = plan
        for (i in startIndex until currentPlan.actions.size) {
            val action = currentPlan.actions[i]

            // Action-by-action approval check
            if (action.requiresApproval && action.state != ActionExecutionState.RUNNING) {
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

                _executionState.value = _executionState.value.copy(
                    status = "Waiting for approval: ${action.action.name}",
                    pendingApproval = currentPlan,
                    pendingActionIndex = i,
                    planToApprove = details
                )
                val approvalPrompt = JarvisSpeechFormatter.formatApprovalPrompt(currentPlan, details)
                voiceSessionController.onWaitingForApproval(approvalPrompt)
                return // Pause execution until user approves
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
                    ToolExecutionResult(
                        ToolExecutionStatus.NOT_IMPLEMENTED,
                        "Command not recognized locally. Requires AI engine."
                    )
                )
                voiceSessionController.speakResponse(unknownSpoken)
                if (!currentPlan.continueOnFailure) {
                    _executionState.value = _executionState.value.copy(status = "Plan stopped: Unrecognized command")
                    return
                }
                continue
            }

            _executionState.value = _executionState.value.copy(status = "Executing ${action.action.name}...")
            val result = toolExecutor.executeAction(action, resolvedContact, isLocalOnly)

            logActivity(
                currentPlan.originalText,
                action,
                "Action ${i + 1}: ${action.action.name}",
                result.status.name,
                result.message
            )

            if (result.status == ToolExecutionStatus.AMBIGUOUS_APP) {
                _executionState.value = _executionState.value.copy(
                    status = "Select app",
                    pendingApproval = currentPlan,
                    pendingActionIndex = i
                )
                val spoken = JarvisSpeechFormatter.formatExecutionResult(currentPlan.originalText, result)
                voiceSessionController.speakResponse(spoken)
                return
            }

            val isSuccess = result.status == ToolExecutionStatus.SUCCESS
            if (!isSuccess && !currentPlan.continueOnFailure) {
                _executionState.value = _executionState.value.copy(status = "Plan stopped: Action ${i + 1} failed")
                val spoken = JarvisSpeechFormatter.formatExecutionResult(currentPlan.originalText, result)
                voiceSessionController.speakResponse(spoken)
                return
            }

            if (i == currentPlan.actions.size - 1) {
                val spoken = JarvisSpeechFormatter.formatExecutionResult(currentPlan.originalText, result)
                voiceSessionController.speakResponse(spoken)
            }
        }

        _executionState.value = _executionState.value.copy(status = "Ready", pendingApproval = null, planToApprove = null)
    }

    private fun logActivity(userQuery: String, action: PlannedAction, details: String, status: String, resultSummary: String) {
        runtimeScope.launch {
            activityRepository.insertLog(
                com.example.data.ActivityLog(
                    command = userQuery,
                    classification = action.action.name,
                    proposedTool = action.targetAppOrPerson ?: action.action.name,
                    status = status,
                    result = resultSummary,
                    approvalRequired = action.requiresApproval
                )
            )
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: JarvisRuntime? = null

        fun getInstance(context: Context): JarvisRuntime {
            return INSTANCE ?: synchronized(this) {
                val instance = JarvisRuntime(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        fun setInstance(instance: JarvisRuntime?) {
            INSTANCE = instance
        }
    }
}
