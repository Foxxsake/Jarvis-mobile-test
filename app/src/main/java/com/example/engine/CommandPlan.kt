package com.example.engine

import com.example.engine.termux.TermuxRiskLevel

enum class CommandCategory {
    DEVICE_ACTION,
    COMMUNICATION,
    DEVELOPMENT,
    RESEARCH,
    AI_TASK,
    UNKNOWN
}

enum class ActionExecutionState {
    PENDING,
    WAITING_FOR_APPROVAL,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
    REJECTED
}

data class CommandProposal(
    val tool: String,
    val workspace: String,
    val command: String,
    val riskLevel: TermuxRiskLevel,
    val reason: String
) {
    fun toFormattedString(): String {
        return "Tool: $tool\nWorkspace: $workspace\nCommand: $command\nRisk: $riskLevel\nReason: $reason"
    }
}

data class PlannedAction(
    val action: CommandAction,
    val category: CommandCategory,
    val targetAppOrPerson: String? = null,
    val rawArguments: String? = null,
    val messageOrQuery: String? = null,
    val followUp: String? = null,
    val requiresApproval: Boolean,
    val continueOnFailure: Boolean = false,
    val riskLevel: TermuxRiskLevel? = null,
    val state: ActionExecutionState = ActionExecutionState.PENDING,
    val proposal: CommandProposal? = null
) {
    val executionState: ActionExecutionState get() = state
}

data class CommandPlan(
    val originalText: String,
    val actions: List<PlannedAction>,
    val continueOnFailure: Boolean = false
) {
    val requiresApproval: Boolean
        get() = actions.any { it.requiresApproval }
}
