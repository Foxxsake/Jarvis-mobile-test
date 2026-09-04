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
    val category: CommandCategory,
    val targetAppOrPerson: String? = null,
    val messageOrQuery: String? = null,
    val requiresApproval: Boolean
)
