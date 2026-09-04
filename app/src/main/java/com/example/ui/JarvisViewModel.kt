package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ActivityLog
import com.example.data.ActivityRepository
import com.example.engine.CommandCategory
import com.example.engine.CommandParser
import com.example.engine.ParsedCommand
import com.example.engine.TaskRouter
import com.example.engine.ToolRegistry
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
    private val commandParser: CommandParser = CommandParser(),
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val taskRouter: TaskRouter = TaskRouter(toolRegistry)
) : ViewModel() {

    val activityLogs: StateFlow<List<ActivityLog>> = repository.allLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val tools = MutableStateFlow(toolRegistry.getTools()).asStateFlow()

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    fun processCommand(text: String, executeAction: (ParsedCommand) -> Unit) {
        if (text.isBlank()) return
        
        _uiState.value = _uiState.value.copy(status = "Planning")

        val parsed = commandParser.parse(text)
        val plan = taskRouter.route(parsed)
        
        if (parsed.requiresApproval) {
            _uiState.value = _uiState.value.copy(
                status = "Waiting for approval",
                pendingApproval = parsed,
                planToApprove = plan.steps.joinToString("\n")
            )
        } else {
            execute(parsed, plan.steps.joinToString(", "), executeAction)
        }
    }

    fun approvePending(executeAction: (ParsedCommand) -> Unit) {
        val pending = _uiState.value.pendingApproval
        val planText = _uiState.value.planToApprove
        if (pending != null) {
            execute(pending, planText ?: "", executeAction)
        }
        clearApproval()
    }

    fun rejectPending() {
        val pending = _uiState.value.pendingApproval
        if (pending != null) {
            logActivity(pending, "Rejected", "User rejected action")
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

    private fun execute(command: ParsedCommand, planText: String, executeAction: (ParsedCommand) -> Unit) {
        if (command.category == CommandCategory.UNKNOWN) {
            logActivity(command, planText, "Skipped (Requires AI)")
            _uiState.value = _uiState.value.copy(status = "Ready")
            return
        }

        executeAction(command)
        logActivity(command, planText, "Executed")
        _uiState.value = _uiState.value.copy(status = "Ready")
    }

    private fun logActivity(command: ParsedCommand, planText: String, result: String) {
        viewModelScope.launch {
            repository.insertLog(
                ActivityLog(
                    command = command.rawText,
                    classification = command.category.name,
                    proposedTool = planText.take(50),
                    status = result,
                    result = result,
                    approvalRequired = command.requiresApproval
                )
            )
        }
    }
}
