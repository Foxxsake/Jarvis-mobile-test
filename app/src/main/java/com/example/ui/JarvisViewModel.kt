package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ActivityLog
import com.example.data.ActivityRepository
import com.example.data.SettingsManager
import com.example.engine.CommandAction
import com.example.engine.CommandParser
import com.example.engine.ParsedCommand
import com.example.engine.TaskRouter
import com.example.engine.ToolExecutor
import com.example.engine.ToolRegistry
import com.example.engine.ToolExecutionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class JarvisUiState(
    val status: String = "Ready",
    val pendingApproval: ParsedCommand? = null,
    val planToApprove: String? = null
)

class JarvisViewModel(
    private val repository: ActivityRepository,
    val settingsManager: SettingsManager,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
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

    val confirmationRequired: StateFlow<Boolean> = settingsManager.confirmationRequiredFlow
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
    }

    fun processCommand(text: String) {
        if (text.isBlank()) return

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
            return
        }

        _uiState.value = _uiState.value.copy(status = "Planning")
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

    fun approvePending() {
        val pending = _uiState.value.pendingApproval
        val planText = _uiState.value.planToApprove
        if (pending != null) {
            execute(pending, planText ?: "")
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

    private fun clearApproval() {
        _uiState.value = _uiState.value.copy(
            status = "Ready",
            pendingApproval = null,
            planToApprove = null
        )
    }

    private fun execute(command: ParsedCommand, planText: String) {
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

        val result = toolExecutor.executeAction(command, localProcessingEnabled.value)
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
}
