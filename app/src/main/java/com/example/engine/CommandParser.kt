package com.example.engine

class CommandParser {
    fun parse(text: String): ParsedCommand {
        val lower = text.trim().lowercase()

        if (lower.startsWith("open ")) {
            val target = lower.removePrefix("open ").trim()
            return ParsedCommand(
                rawText = text,
                category = CommandCategory.DEVICE_ACTION,
                targetAppOrPerson = target,
                requiresApproval = false
            )
        }

        if (lower.startsWith("call ")) {
            val name = lower.removePrefix("call ").trim()
            return ParsedCommand(
                rawText = text,
                category = CommandCategory.COMMUNICATION,
                targetAppOrPerson = name,
                requiresApproval = true
            )
        }

        if (lower.startsWith("text ")) {
            val parts = lower.removePrefix("text ").trim().split(" ", limit = 2)
            val name = parts.getOrNull(0)
            val message = parts.getOrNull(1)
            return ParsedCommand(
                rawText = text,
                category = CommandCategory.COMMUNICATION,
                targetAppOrPerson = name,
                messageOrQuery = message,
                requiresApproval = true
            )
        }

        if (lower.startsWith("email ")) {
            val parts = lower.removePrefix("email ").trim().split(" ", limit = 2)
            val name = parts.getOrNull(0)
            val message = parts.getOrNull(1)
            return ParsedCommand(
                rawText = text,
                category = CommandCategory.COMMUNICATION,
                targetAppOrPerson = name,
                messageOrQuery = message,
                requiresApproval = true
            )
        }

        if (lower.startsWith("build ") || lower.startsWith("work on ") || lower.startsWith("check github")) {
            return ParsedCommand(
                rawText = text,
                category = CommandCategory.DEVELOPMENT,
                requiresApproval = true
            )
        }

        return ParsedCommand(
            rawText = text,
            category = CommandCategory.UNKNOWN,
            requiresApproval = false
        )
    }
}
