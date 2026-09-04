package com.example.engine

class CommandParser(
    private val approvalManager: ApprovalManager = ApprovalManager(),
    private val toolMatcher: ToolCommandMatcher = ToolCommandMatcher()
) {

    fun parse(text: String): CommandPlan {
        if (text.isBlank()) {
            return CommandPlan(
                originalText = text,
                actions = listOf(
                    PlannedAction(
                        action = CommandAction.UNKNOWN,
                        category = CommandCategory.UNKNOWN,
                        requiresApproval = false
                    )
                )
            )
        }

        val actions = mutableListOf<PlannedAction>()
        var remainingText: String? = text.trim()

        while (!remainingText.isNullOrBlank()) {
            val parsedSingle = parseSingle(remainingText)

            if (parsedSingle.action == CommandAction.UNKNOWN) {
                if (actions.isNotEmpty()) {
                    val last = actions.removeAt(actions.lastIndex)
                    actions.add(last.copy(
                        followUp = (last.followUp?.let { "$it and " } ?: "") + remainingText,
                        rawArguments = (last.rawArguments?.let { "$it and " } ?: "") + remainingText,
                    ))
                } else {
                    actions.add(parsedSingle)
                }
                break
            } else {
                if (!parsedSingle.followUp.isNullOrBlank()) {
                    val nextText = parsedSingle.followUp
                    val actionWithoutFollowUp = parsedSingle.copy(followUp = null, rawArguments = null)

                    val nextParsed = parseSingle(nextText)
                    if (nextParsed.action != CommandAction.UNKNOWN) {
                        actions.add(actionWithoutFollowUp)
                        remainingText = nextText
                    } else {
                        actions.add(parsedSingle)
                        break
                    }
                } else {
                    actions.add(parsedSingle)
                    break
                }
            }
        }

        return CommandPlan(originalText = text, actions = actions)
    }

    private fun parseSingle(text: String): PlannedAction {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        if (lower == "open settings" || lower == "settings") {
            return PlannedAction(
                action = CommandAction.OPEN_SETTINGS,
                category = CommandCategory.DEVICE_ACTION,
                targetAppOrPerson = "settings",
                requiresApproval = approvalManager.requiresApproval(CommandAction.OPEN_SETTINGS, CommandCategory.DEVICE_ACTION, text)
            )
        }

        if (lower.startsWith("call ")) {
            val target = trimmed.substring(5).trim()
            return PlannedAction(
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

            return PlannedAction(
                action = action,
                category = CommandCategory.COMMUNICATION,
                targetAppOrPerson = target,
                rawArguments = remainder,
                messageOrQuery = message,
                requiresApproval = approvalManager.requiresApproval(action, CommandCategory.COMMUNICATION, text)
            )
        }

        if (lower == "check project status" || lower == "project status") {
            return PlannedAction(
                action = CommandAction.CHECK_PROJECT_STATUS,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed,
                requiresApproval = approvalManager.requiresApproval(CommandAction.CHECK_PROJECT_STATUS, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower == "check termux" || lower == "check git version" || lower == "check node version" ||
            lower == "check npm version" || lower == "check python version" || lower == "check git status") {
            val termuxCmd = when (lower) {
                "check termux" -> "whoami"
                "check git version" -> "git --version"
                "check node version" -> "node --version"
                "check npm version" -> "npm --version"
                "check python version" -> "python --version"
                "check git status" -> "git status"
                else -> "whoami"
            }
            return PlannedAction(
                action = CommandAction.TERMUX_COMMAND,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = termuxCmd,
                requiresApproval = approvalManager.requiresApproval(CommandAction.TERMUX_COMMAND, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("termux ")) {
            val cmd = trimmed.substring(7).trim()
            return PlannedAction(
                action = CommandAction.TERMUX_COMMAND,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = cmd,
                requiresApproval = approvalManager.requiresApproval(CommandAction.TERMUX_COMMAND, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower == "run tests" || lower == "run test") {
            return PlannedAction(
                action = CommandAction.TERMUX_COMMAND,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = "test",
                requiresApproval = approvalManager.requiresApproval(CommandAction.TERMUX_COMMAND, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower == "build project" || lower == "build app") {
            return PlannedAction(
                action = CommandAction.TERMUX_COMMAND,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = "build",
                requiresApproval = approvalManager.requiresApproval(CommandAction.TERMUX_COMMAND, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower == "check github" || lower.startsWith("check github ")) {
            return PlannedAction(
                action = CommandAction.CHECK_GITHUB,
                category = CommandCategory.DEVELOPMENT,
                targetAppOrPerson = "github",
                rawArguments = trimmed.removePrefix("check ").trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.CHECK_GITHUB, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("build ")) {
            return PlannedAction(
                action = CommandAction.BUILD,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.substring(6).trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.BUILD, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("work on ")) {
            return PlannedAction(
                action = CommandAction.WORK_ON,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.substring(8).trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.WORK_ON, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("push ") || lower == "push") {
            return PlannedAction(
                action = CommandAction.PUSH,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.removePrefix("push").trim().ifBlank { null },
                requiresApproval = approvalManager.requiresApproval(CommandAction.PUSH, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("delete ")) {
            return PlannedAction(
                action = CommandAction.DELETE,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.substring(7).trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.DELETE, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("overwrite ")) {
            return PlannedAction(
                action = CommandAction.OVERWRITE,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.substring(10).trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.OVERWRITE, CommandCategory.DEVELOPMENT, text)
            )
        }

        if (lower.startsWith("run ")) {
            return PlannedAction(
                action = CommandAction.RUN_COMMAND,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.substring(4).trim(),
                requiresApproval = approvalManager.requiresApproval(CommandAction.RUN_COMMAND, CommandCategory.DEVELOPMENT, text)
            )
        }

        // Check for app opening / natural commands
        val isOpenVerb = lower.startsWith("open ") || lower.startsWith("launch ") ||
                lower.startsWith("start ") || lower.startsWith("go to ") ||
                lower.startsWith("please ")

        if (isOpenVerb) {
            val match = toolMatcher.match(trimmed)
            if (match is ToolMatchOutcome.Success) {
                val target = if (match.result.matchedTerm.equals(match.result.tool.name, ignoreCase = true)) {
                    match.result.tool.name
                } else {
                    match.result.matchedTerm
                }
                return PlannedAction(
                    action = CommandAction.OPEN_APP,
                    category = CommandCategory.DEVICE_ACTION,
                    targetAppOrPerson = target,
                    rawArguments = match.result.followUp,
                    followUp = match.result.followUp,
                    requiresApproval = approvalManager.requiresApproval(CommandAction.OPEN_APP, CommandCategory.DEVICE_ACTION, text)
                )
            } else if (lower.startsWith("open ")) {
                val target = trimmed.substring(5).trim()
                return PlannedAction(
                    action = CommandAction.OPEN_APP,
                    category = CommandCategory.DEVICE_ACTION,
                    targetAppOrPerson = target,
                    requiresApproval = approvalManager.requiresApproval(CommandAction.OPEN_APP, CommandCategory.DEVICE_ACTION, text)
                )
            }
        }

        // Direct tool name or alias match without opening verb (e.g., "Pyroid", "Pydroid 3", "Termux")
        val directMatch = toolMatcher.match(trimmed)
        if (directMatch is ToolMatchOutcome.Success) {
            val target = if (directMatch.result.matchedTerm.equals(directMatch.result.tool.name, ignoreCase = true)) {
                directMatch.result.tool.name
            } else {
                directMatch.result.matchedTerm
            }
            return PlannedAction(
                action = CommandAction.OPEN_APP,
                category = CommandCategory.DEVICE_ACTION,
                targetAppOrPerson = target,
                rawArguments = directMatch.result.followUp,
                followUp = directMatch.result.followUp,
                requiresApproval = approvalManager.requiresApproval(CommandAction.OPEN_APP, CommandCategory.DEVICE_ACTION, text)
            )
        }

        return PlannedAction(
            action = CommandAction.UNKNOWN,
            category = CommandCategory.UNKNOWN,
            requiresApproval = false
        )
    }
}
