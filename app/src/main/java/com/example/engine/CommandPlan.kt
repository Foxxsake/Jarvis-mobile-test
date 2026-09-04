package com.example.engine

enum class CommandCategory {
    DEVICE_ACTION,
    COMMUNICATION,
    DEVELOPMENT,
    RESEARCH,
    AI_TASK,
    UNKNOWN
}

data class PlannedAction(
    val action: CommandAction,
    val category: CommandCategory,
    val targetAppOrPerson: String? = null,
    val rawArguments: String? = null,
    val messageOrQuery: String? = null,
    val followUp: String? = null,
    val requiresApproval: Boolean
)

data class CommandPlan(
    val originalText: String,
    val actions: List<PlannedAction>
) {
    val requiresApproval: Boolean
        get() = actions.any { it.requiresApproval }
}
