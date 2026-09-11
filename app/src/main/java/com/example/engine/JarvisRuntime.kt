package com.example.engine

import android.content.Context
import android.util.Log
import com.example.data.AccessPolicy
import com.example.data.ActivityRepository
import com.example.data.AppDatabase
import com.example.data.SettingsManager
import com.example.data.workspace.LocalWorkspaceRegistry
import com.example.data.workspace.Workspace
import com.example.data.workspace.WorkspaceRegistry
import com.example.engine.ai.GeminiProvider
import com.example.engine.audio.AudioSessionManager
import com.example.engine.contacts.AndroidContactsProvider
import com.example.engine.contacts.ContactCandidate
import com.example.engine.contacts.ContactDestination
import com.example.engine.contacts.ContactResolutionResult
import com.example.engine.policy.ExecutionPolicyGuard
import com.example.engine.policy.PolicyDecision
import com.example.engine.speech.SpeechManager
import com.example.engine.termux.AndroidTermuxWorker
import com.example.engine.termux.TermuxCommandClassifier
import com.example.engine.termux.TermuxConnectionState
import com.example.engine.termux.TermuxConnectionStatus
import com.example.engine.termux.TermuxExecutionResult
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "JarvisRuntime"

data class RuntimeExecutionState(
    val status: String = "Ready",
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
    val lastRecognizedText: String = "",
    val speechEventId: Long = 0L,
    val termuxStatus: TermuxConnectionStatus = TermuxConnectionStatus(
        isInstalled = false,
        isPermissionGranted = false,
        isExternalAppsAllowed = false,
        connectionState = TermuxConnectionState.UNVERIFIED
    ),
    val activeWorkspace: Workspace? = null,
    val lastTermuxResult: TermuxExecutionResult? = null
)

class JarvisRuntime private constructor(val context: Context) {

    val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val executionMutex = Mutex()

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

    val audioSessionManager: AudioSessionManager = AudioSessionManager()
    val speechManager: SpeechManager = SpeechManager(context)
    val voiceOutput: JarvisVoiceOutput = AndroidVoiceOutput(context)

    val voiceSessionController: VoiceSessionController = VoiceSessionController(
        speechManager = speechManager,
        voiceOutput = voiceOutput,
        audioSessionManager = audioSessionManager
    )

    val parser: CommandParser = CommandParser(toolMatcher = ToolCommandMatcher { toolRegistry.tools.value })
    val taskRouter: TaskRouter = TaskRouter(toolRegistry)

    // Gemini AI provider - lazily initialized when API key is available
    private var geminiProvider: GeminiProvider? = null

    private val _executionState = MutableStateFlow(RuntimeExecutionState())
    val executionState: StateFlow<RuntimeExecutionState> = _executionState.asStateFlow()

    init {
        runtimeScope.launch {
            settingsManager.spokenResponsesFlow.collectLatest { enabled ->
                voiceSessionController.setSpokenResponsesEnabled(enabled)
            }
        }

        runtimeScope.launch {
            speechManager.speechState.collectLatest { speechState ->
                voiceSessionController.handleSpeechState(speechState)
            }
        }

        voiceSessionController.addListener(object : VoiceSessionListener {
            override fun onStateChanged(state: VoiceSessionState) {
                val statusText = when (state) {
                    VoiceSessionState.IDLE -> if (_executionState.value.pendingApproval != null) "Waiting for approval" else "Ready"
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
                _executionState.value = _executionState.value.copy(
                    lastRecognizedText = text,
                    speechEventId = System.currentTimeMillis()
                )
                executeCommand(text)
            }

            override fun onError(message: String) {
                _executionState.value = _executionState.value.copy(status = "Error: $message")
            }
        })

        runtimeScope.launch {
            val initialStatus = termuxWorker.checkConnectionState()
            _executionState.value = _executionState.value.copy(termuxStatus = initialStatus)
            if (initialStatus.isInstalled && initialStatus.isPermissionGranted) {
                probeTermuxConnection()
            }
        }
        refreshActiveWorkspace()
    }

    /**
     * Sets the Gemini API key and initializes the AI provider.
     */
    fun setApiKey(apiKey: String) {
        if (apiKey.isNotBlank()) {
            geminiProvider = GeminiProvider(apiKey)
            Log.i(TAG, "Gemini AI provider initialized")
        }
    }

    fun executeCommand(text: String) {
        if (text.isBlank()) return

        runtimeScope.launch {
            executionMutex.withLock {
                if (_executionState.value.pendingApproval != null) {
                    _executionState.value = _executionState.value.copy(
                        status = "Action pending approval. Please approve or reject first."
                    )
                    voiceSessionController.speakResponse("Please resolve the pending approval first.")
                    return@launch
                }

                val plan = parser.parse(text)
                val isLocalOnly = kotlinx.coroutines.withTimeoutOrNull(200) { settingsManager.localProcessingFlow.firstOrNull() } ?: true

                if (plan.actions.size == 1 && plan.actions[0].action == CommandAction.OPEN_APP &&
                    plan.actions[0].candidateTools != null && plan.actions[0].candidateTools!!.size > 1
                ) {
                    _executionState.value = _executionState.value.copy(
                        status = "Select app",
                        pendingApproval = plan,
                        ambiguousAppQuery = plan.actions[0].targetAppOrPerson,
                        ambiguousAppCandidates = plan.actions[0].candidateTools
                    )
                    voiceSessionController.speakResponse("Multiple apps matched. Please select on screen.")
                    return@launch
                }

                if (plan.actions.size == 1 && (plan.actions[0].action == CommandAction.CALL ||
                            plan.actions[0].action == CommandAction.TEXT ||
                            plan.actions[0].action == CommandAction.EMAIL)
                ) {
                    val resolution = contactResolver.resolveCommandTarget(plan.actions[0])
                    handleContactResolution(plan, resolution)
                    return@launch
                }

                val resolvedPlan = resolveDynamicProposals(plan)

                // If the command is UNKNOWN and we have an AI provider, try AI fallback
                if (resolvedPlan.actions.size == 1 && resolvedPlan.actions[0].action == CommandAction.UNKNOWN) {
                    val aiResult = tryAiFallback(text, resolvedPlan)
                    if (aiResult != null) {
                        val aiIsLocalOnly = kotlinx.coroutines.withTimeoutOrNull(200) { settingsManager.localProcessingFlow.firstOrNull() } ?: true
                        executePlanInternal(aiResult, startIndex = 0, isLocalOnly = aiIsLocalOnly)
                        return@launch
                    }
                }

                executePlanInternal(resolvedPlan, startIndex = 0, isLocalOnly = isLocalOnly)
            }
        }
    }

    /**
     * Attempts to use the Gemini AI provider to handle an unrecognized command.
     */
    private suspend fun tryAiFallback(text: String, originalPlan: CommandPlan): CommandPlan? {
        val provider = geminiProvider ?: return null

        return try {
            _executionState.value = _executionState.value.copy(status = "Thinking...")
            val aiResponse = provider.generateResponse(text)
            val aiPlan = provider.parseResponseToPlan(aiResponse, text)

            if (aiPlan != null && aiPlan.actions.firstOrNull()?.action != CommandAction.UNKNOWN) {
                Log.d(TAG, "AI fallback parsed command: ${aiPlan.actions.firstOrNull()?.action}")
                aiPlan
            } else {
                // If AI didn't parse to an action, use the AI's spoken response directly
                val spokenResponse = extractSpokenResponse(aiResponse)
                if (spokenResponse.isNotBlank()) {
                    voiceSessionController.speakResponse(spokenResponse)
                    logActivity(text, originalPlan.actions.first(), "AI Response", "SUCCESS", spokenResponse)
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI fallback failed", e)
            null
        }
    }

    private fun extractSpokenResponse(aiResponse: String): String {
        // Try to extract the "response" field from JSON
        return try {
            val jsonStr = aiResponse.trim()
            if (jsonStr.startsWith("{")) {
                val json = org.json.JSONObject(jsonStr)
                json.optString("response", aiResponse)
            } else {
                aiResponse
            }
        } catch (_: Exception) {
            aiResponse
        }
    }

    private fun resolveDynamicProposals(plan: CommandPlan): CommandPlan {
        val updatedActions = plan.actions.map { action ->
            if (action.action == CommandAction.TERMUX_COMMAND && action.proposal == null) {
                val workspace = workspaceRegistry.getActiveWorkspace()
                val wsName = workspace?.displayName ?: "No Workspace"
                val raw = action.rawArguments?.lowercase() ?: ""
                val (cmd, reason) = when {
                    raw.contains("test") -> "./gradlew test" to "Run project tests"
                    raw.contains("build") -> "./gradlew assembleDebug" to "Build project"
                    else -> (action.rawArguments ?: "") to "Execute Termux command"
                }
                val risk = TermuxCommandClassifier.classifyCommandLine(cmd)
                action.copy(
                    proposal = CommandProposal(
                        tool = "Termux",
                        workspace = wsName,
                        command = cmd,
                        riskLevel = risk,
                        reason = reason
                    ),
                    riskLevel = risk
                )
            } else {
                action
            }
        }
        return plan.copy(actions = updatedActions)
    }

    private suspend fun executePlanInternal(
        plan: CommandPlan,
        startIndex: Int = 0,
        resolvedContact: ContactResolutionResult? = null,
        isLocalOnly: Boolean = true,
        isApprovedByUser: Boolean = false
    ) {
        var currentPlan = plan

        if (startIndex == 0) {
            val executionPlan = taskRouter.route(currentPlan)
            _executionState.value = _executionState.value.copy(
                status = "Executing...",
                pendingApproval = null,
                planToApprove = null
            )
        }

        for (i in startIndex until currentPlan.actions.size) {
            val action = currentPlan.actions[i]

            val policyDecision = ExecutionPolicyGuard.evaluate(
                action = action,
                toolRegistry = toolRegistry,
                isApprovedByUser = isApprovedByUser
            )

            when (policyDecision) {
                is PolicyDecision.Blocked -> {
                    logActivity(
                        currentPlan.originalText, action, "Action ${i + 1}",
                        ToolExecutionStatus.FAILED.name, policyDecision.reason
                    )
                    val spoken = "Action blocked: ${policyDecision.reason}"
                    voiceSessionController.speakResponse(spoken)
                    return
                }
                is PolicyDecision.RequiresApproval -> {
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
                        } else {
                            appendLine(policyDecision.promptDetails)
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
                    return
                }
                is PolicyDecision.Allowed -> {
                    // Passed safety checks, proceed to execution
                }
            }

            if (action.action == CommandAction.UNKNOWN) {
                // Try AI fallback before giving up
                val aiPlan = tryAiFallback(currentPlan.originalText, currentPlan)
                if (aiPlan != null) {
                    currentPlan = aiPlan
                    continue
                }

                logActivity(
                    currentPlan.originalText, action, "Action ${i + 1}",
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
            val authorization = com.example.engine.policy.ExecutionAuthorization.userApproved()
            val result = toolExecutor.executeAction(action, resolvedContact, isLocalOnly, authorization)

            logActivity(
                currentPlan.originalText, action,
                "Action ${i + 1}: ${action.action.name}",
                result.status.name, result.message
            )

            if (result.status == ToolExecutionStatus.AMBIGUOUS_APP) {
                _executionState.value = _executionState.value.copy(
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

        _executionState.value = _executionState.value.copy(
            status = "Ready",
            pendingApproval = null,
            pendingActionIndex = 0,
            planToApprove = null,
            resolvedContact = null,
            ambiguousCandidates = null,
            multipleDestinations = null,
            ambiguousAppCandidates = null
        )
    }

    fun approvePending() {
        val plan = _executionState.value.pendingApproval ?: return
        val index = _executionState.value.pendingActionIndex
        val resolved = _executionState.value.resolvedContact

        runtimeScope.launch {
            executionMutex.withLock {
                _executionState.value = _executionState.value.copy(
                    status = "Approved. Executing...",
                    pendingApproval = null,
                    planToApprove = null
                )

                val isLocalOnly = settingsManager.localProcessingFlow.firstOrNull() ?: true
                val updatedActions = plan.actions.mapIndexed { idx, act ->
                    if (idx == index) act.copy(state = ActionExecutionState.RUNNING) else act
                }
                val updatedPlan = plan.copy(actions = updatedActions)

                executePlanInternal(
                    plan = updatedPlan,
                    startIndex = index,
                    resolvedContact = resolved,
                    isLocalOnly = isLocalOnly,
                    isApprovedByUser = true
                )
            }
        }
    }

    fun rejectPending() {
        val plan = _executionState.value.pendingApproval
        val action = plan?.actions?.getOrNull(_executionState.value.pendingActionIndex) ?: PlannedAction(
            action = CommandAction.UNKNOWN,
            category = CommandCategory.UNKNOWN,
            requiresApproval = false
        )

        if (plan != null) {
            logActivity(plan.originalText, action, "Approval", ToolExecutionStatus.SKIPPED.name, "User rejected approval.")
        }

        _executionState.value = _executionState.value.copy(
            status = "Action cancelled",
            pendingApproval = null,
            pendingActionIndex = 0,
            planToApprove = null,
            resolvedContact = null,
            ambiguousQuery = null,
            ambiguousCandidates = null,
            multipleDestinations = null,
            ambiguousAppQuery = null,
            ambiguousAppCandidates = null,
            permissionRationaleNeeded = null
        )
        voiceSessionController.speakResponse("Action cancelled.")
    }

    private fun handleContactResolution(plan: CommandPlan, resolution: ContactResolutionResult) {
        when (resolution) {
            is ContactResolutionResult.Resolved -> {
                val maskedDest = when {
                    plan.actions[0].action == CommandAction.EMAIL -> resolution.destination.value
                    else -> PrivacyUtils.maskPhoneNumber(resolution.destination.value)
                }
                _executionState.value = _executionState.value.copy(
                    resolvedContact = resolution,
                    status = "Resolved: ${resolution.displayName} ($maskedDest)"
                )
                runtimeScope.launch {
                    executionMutex.withLock {
                        val isLocalOnly = settingsManager.localProcessingFlow.firstOrNull() ?: true
                        executePlanInternal(plan, startIndex = 0, resolvedContact = resolution, isLocalOnly = isLocalOnly)
                    }
                }
            }
            is ContactResolutionResult.Ambiguous -> {
                _executionState.value = _executionState.value.copy(
                    status = "Multiple contacts found",
                    pendingApproval = plan,
                    ambiguousQuery = resolution.query,
                    ambiguousCandidates = resolution.candidates
                )
                voiceSessionController.speakResponse("Multiple contacts found for ${resolution.query}. Please select on screen.")
            }
            is ContactResolutionResult.MultipleDestinations -> {
                _executionState.value = _executionState.value.copy(
                    status = "Multiple destinations found",
                    pendingApproval = plan,
                    multipleDestinationsName = resolution.displayName,
                    multipleDestinations = resolution.destinations,
                    pendingMessageForDestination = resolution.message
                )
                voiceSessionController.speakResponse("${resolution.displayName} has multiple contact details. Please select on screen.")
            }
            is ContactResolutionResult.ResolutionRequired -> {
                _executionState.value = _executionState.value.copy(status = "Contact details missing")
                voiceSessionController.speakResponse("Please specify who you want to contact.")
            }
            is ContactResolutionResult.PermissionRequired -> {
                _executionState.value = _executionState.value.copy(
                    status = "Permission required",
                    permissionRationaleNeeded = "CONTACTS"
                )
            }
            is ContactResolutionResult.NotFound -> {
                logActivity(plan.originalText, plan.actions.first(), "Contact lookup", ToolExecutionStatus.FAILED.name, "Contact not found")
                _executionState.value = _executionState.value.copy(status = "Contact not found")
                voiceSessionController.speakResponse("Contact not found")
            }
            is ContactResolutionResult.ProviderError -> {
                logActivity(plan.originalText, plan.actions.first(), "Contact lookup", ToolExecutionStatus.FAILED.name, resolution.message)
                _executionState.value = _executionState.value.copy(status = "Contact error")
                voiceSessionController.speakResponse(resolution.message)
            }
        }
    }

    fun selectContactCandidate(candidate: ContactCandidate) {
        val plan = _executionState.value.pendingApproval ?: return
        val action = plan.actions.firstOrNull() ?: return
        val message = action.messageOrQuery

        runtimeScope.launch {
            executionMutex.withLock {
                val resolution = contactResolver.resolveCandidateDestinations(candidate, message)
                _executionState.value = _executionState.value.copy(
                    ambiguousQuery = null,
                    ambiguousCandidates = null
                )
                handleContactResolution(plan, resolution)
            }
        }
    }

    fun selectContactDestination(destination: ContactDestination) {
        val plan = _executionState.value.pendingApproval ?: return
        val action = plan.actions.firstOrNull() ?: return
        val name = _executionState.value.multipleDestinationsName ?: "Contact"
        val message = _executionState.value.pendingMessageForDestination ?: action.messageOrQuery

        runtimeScope.launch {
            executionMutex.withLock {
                val candidate = ContactCandidate(contactId = name, displayName = name, destinations = listOf(destination))
                val resolution = contactResolver.resolveCandidateDestinations(candidate, message)
                _executionState.value = _executionState.value.copy(
                    multipleDestinationsName = null,
                    multipleDestinations = null,
                    pendingMessageForDestination = null
                )
                handleContactResolution(plan, resolution)
            }
        }
    }

    fun selectAppCandidate(tool: Tool) {
        val plan = _executionState.value.pendingApproval ?: return
        val index = _executionState.value.pendingActionIndex
        val action = plan.actions.getOrNull(index) ?: return

        runtimeScope.launch {
            executionMutex.withLock {
                val updatedAction = action.copy(
                    targetAppOrPerson = tool.name,
                    candidateTools = listOf(tool)
                )
                val updatedActions = plan.actions.toMutableList().apply {
                    set(index, updatedAction)
                }
                val updatedPlan = plan.copy(actions = updatedActions)
                val isLocalOnly = settingsManager.localProcessingFlow.firstOrNull() ?: true

                _executionState.value = _executionState.value.copy(
                    ambiguousAppQuery = null,
                    ambiguousAppCandidates = null
                )
                executePlanInternal(updatedPlan, startIndex = index, isLocalOnly = isLocalOnly)
            }
        }
    }

    fun dismissPermissionRationale() {
        _executionState.value = _executionState.value.copy(permissionRationaleNeeded = null)
    }

    fun dismissPermissionPermanentlyDenied() {
        _executionState.value = _executionState.value.copy(permissionPermanentlyDenied = null)
    }

    fun showPermissionRationale(permType: String) {
        _executionState.value = _executionState.value.copy(permissionRationaleNeeded = permType)
    }

    fun showPermissionPermanentlyDenied(permType: String) {
        _executionState.value = _executionState.value.copy(permissionPermanentlyDenied = permType)
    }

    fun refreshActiveWorkspace() {
        runtimeScope.launch {
            val ws = workspaceRegistry.getActiveWorkspace()
            _executionState.value = _executionState.value.copy(activeWorkspace = ws)
        }
    }

    fun setWorkspacePath(displayName: String, path: String) {
        runtimeScope.launch {
            val ws = Workspace(
                id = "ws_${System.currentTimeMillis()}",
                displayName = displayName,
                localPath = path
            )
            workspaceRegistry.setActiveWorkspace(ws)
            refreshActiveWorkspace()
        }
    }

    suspend fun probeTermuxConnection(): TermuxConnectionStatus {
        val current = _executionState.value.termuxStatus
        val verifyingStatus = current.copy(
            connectionState = TermuxConnectionState.VERIFYING,
            detailMessage = "Probing Termux connection..."
        )
        _executionState.value = _executionState.value.copy(termuxStatus = verifyingStatus)

        val result = termuxWorker.probeConnection()
        _executionState.value = _executionState.value.copy(termuxStatus = result)
        return result
    }

    fun refreshTermuxStatus() {
        runtimeScope.launch {
            probeTermuxConnection()
        }
    }

    fun toggleToolEnabled(toolId: String, enabled: Boolean) {
        val currentTools = toolRegistry.tools.value
        val disabled = currentTools.filter { if (it.id == toolId) !enabled else !it.enabled }.map { it.id }.toSet()
        toolRegistry.updateDisabledTools(disabled)
    }

    fun updateToolPolicy(tool: Tool, policy: AccessPolicy) {
        runtimeScope.launch {
            toolRegistry.setToolPolicy(tool, policy)
        }
    }

    fun updateAppPolicy(packageName: String, policy: AccessPolicy) {
        runtimeScope.launch {
            toolRegistry.setAppPolicy(packageName, policy)
        }
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

