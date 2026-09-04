package com.example.engine

enum class CommandCategory {
    DEVICE_ACTION,
    COMMUNICATION,
    DEVELOPMENT,
    RESEARCH,
    AI_TASK,
    UNKNOWN
}

data class ParsedCommand(
    val rawText: String,
    val action: CommandAction,
    val category: CommandCategory,
    val targetAppOrPerson: String? = null,
    val rawArguments: String? = null,
    val messageOrQuery: String? = null,
    val followUp: String? = null,
    val requiresApproval: Boolean
)
