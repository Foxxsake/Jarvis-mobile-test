package com.example.engine

class CommandParser(private val approvalManager: ApprovalManager = ApprovalManager()) {
    fun parse(text: String): ParsedCommand {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        if (lower == "open settings" || lower == "settings") {
            return ParsedCommand(
                rawText = text,
                action = CommandAction.OPEN_SETTINGS,
                category = CommandCategory.DEVICE_ACTION,
                targetAppOrPerson = "settings",
                requiresApproval = approvalManager.requiresApproval(CommandAction.OPEN_SETTINGS, CommandCategory.DEVICE_ACTION, text)
            )
        }

        if (lower.startsWith("open ")) {
            val target = trimmed.substring(5).trim()
            return ParsedCommand(
                rawText = text,
                action = CommandAction.OPEN_APP,
                category = CommandCategory.DEVICE_ACTION,
                targetAppOrPerson = target,
                requiresApproval = approvalManager.requiresApproval(CommandAction.OPEN_APP, CommandCategory.DEVICE_ACTION, text)
            )
        }

        if (lower.startsWith("call ")) {
            val target = trimmed.substring(5).trim()
            return ParsedCommand(
                rawText = text,
                action = CommandAction.CALL,
                category = CommandCategory.COMMUNICATION,
                targetAppOrPerson = target,
                rawArguments = target,
                requiresApproval = approvalManager.requiresApproval(CommandAction.CALL, CommandCategory.COMMUNICATION, text)
            )
        }

        if (lower.startsWith("text ") || lower.startsWith("email ")) {
            val isText = lower.startsWith("text ")
            val action = if (isText) CommandAction.TEXT else CommandAction.EMAIL
            val prefixLength = if (isText) 5 else 6
            val remainder = trimmed.substring(prefixLength).trim()

            val target: String?
            val message: String?
            if (remainder.contains(":")) {
                val colonIdx = remainder.indexOf(":")
                target = remainder.substring(0, colonIdx).trim().ifBlank { null }
                message = remainder.substring(colonIdx + 1).trim().ifBlank { null }
            } else {
                target = null
                message = null
            }

            return ParsedCommand(
                rawText = text,
                action = action,
                category = CommandCategory.COMMUNICATION,
                targetAppOrPerson = target,
                rawArguments = remainder,
                messageOrQuery = message,
                requiresApproval = approvalManager.requiresApproval(action, CommandCategory.COMMUNICATION, text)
            )
        }

        if (lower == "check github" || lower.startsWith("check github ")) {
            return ParsedCommand(
                rawText = text,
                action = CommandAction.CHECK_GITHUB,
                category = CommandCategory.DEVELOPMENT,
                targetAppOrPerson = "github",
                rawArguments = trimmed.removePrefix("check ").trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.CHECK_GITHUB, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("build ")) {
            return ParsedCommand(
                rawText = text,
                action = CommandAction.BUILD,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.substring(6).trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.BUILD, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("work on ")) {
            return ParsedCommand(
                rawText = text,
                action = CommandAction.WORK_ON,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.substring(8).trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.WORK_ON, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("push ") || lower == "push") {
            return ParsedCommand(
                rawText = text,
                action = CommandAction.PUSH,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.removePrefix("push").trim().ifBlank { null },
                requiresApproval = approvalManager.requiresApproval(CommandAction.PUSH, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("delete ")) {
            return ParsedCommand(
                rawText = text,
                action = CommandAction.DELETE,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.substring(7).trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.DELETE, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("overwrite ")) {
            return ParsedCommand(
                rawText = text,
                action = CommandAction.OVERWRITE,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.substring(10).trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.OVERWRITE, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("run ")) {
            return ParsedCommand(
                rawText = text,
                action = CommandAction.RUN_COMMAND,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.substring(4).trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.RUN_COMMAND, CommandCategory.DEVELOPMENT, text)
            )
        }

        return ParsedCommand(
            rawText = text,
            action = CommandAction.UNKNOWN,
            category = CommandCategory.UNKNOWN,
            requiresApproval = false
        )
    }
}
