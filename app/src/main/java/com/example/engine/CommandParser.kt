package com.example.engine

import com.example.engine.termux.TermuxCommandClassifier
import com.example.engine.termux.TermuxRiskLevel

class CommandParser(
    private val approvalManager: ApprovalManager = ApprovalManager(),
    private val toolMatcher: ToolCommandMatcher = ToolCommandMatcher()
) {

    fun parse(text: String): CommandPlan {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
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

        val actions = trySplitActions(trimmed)
        return CommandPlan(originalText = text, actions = actions)
    }

    private fun trySplitActions(text: String): List<PlannedAction> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Natural connectors and punctuation-based separators
        val connectorPatterns = listOf(
            Regex(";\\s*"),
            Regex("\\s+and\\s+then\\s+", RegexOption.IGNORE_CASE),
            Regex("\\s*,\\s*then\\s+", RegexOption.IGNORE_CASE),
            Regex("\\s+then\\s+", RegexOption.IGNORE_CASE),
            Regex("\\s*,\\s*and\\s+", RegexOption.IGNORE_CASE),
            Regex("\\s+and\\s+", RegexOption.IGNORE_CASE),
            Regex("\\s*,\\s*")
        )

        for (pattern in connectorPatterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val left = trimmed.substring(0, match.range.first).trim()
                val right = trimmed.substring(match.range.last + 1).trim()

                if (left.isNotBlank() && right.isNotBlank()) {
                    val leftAction = parseSingle(left)
                    if (leftAction.action != CommandAction.UNKNOWN) {
                        val rightActions = trySplitActions(right)
                        if (rightActions.isNotEmpty() && rightActions.none { it.action == CommandAction.UNKNOWN }) {
                            return listOf(leftAction) + rightActions
                        } else {
                            // If the second segment is not independently recognised, preserve it as a follow-up instead
                            return listOf(leftAction.copy(followUp = right))
                        }
                    }
                }
            }
        }

        // If no multi-action split produces valid known actions, parse as single
        return listOf(parseSingle(trimmed))
    }

    fun parseSingle(text: String): PlannedAction {
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
                riskLevel = TermuxRiskLevel.READ_ONLY,
                requiresApproval = false
            )
        }

        // Check commands (diagnostics, versions, voice tool resolution)
        if (lower.startsWith("check ")) {
            val target = lower.substring(6).trim()
            when (target) {
                "project status" -> {
                    return PlannedAction(
                        action = CommandAction.CHECK_PROJECT_STATUS,
                        category = CommandCategory.DEVELOPMENT,
                        rawArguments = trimmed,
                        riskLevel = TermuxRiskLevel.READ_ONLY,
                        requiresApproval = false
                    )
                }
                "git version" -> {
                    return PlannedAction(
                        action = CommandAction.TERMUX_COMMAND,
                        category = CommandCategory.DEVELOPMENT,
                        rawArguments = "git --version",
                        riskLevel = TermuxRiskLevel.READ_ONLY,
                        requiresApproval = false
                    )
                }
                "node version" -> {
                    return PlannedAction(
                        action = CommandAction.TERMUX_COMMAND,
                        category = CommandCategory.DEVELOPMENT,
                        rawArguments = "node --version",
                        riskLevel = TermuxRiskLevel.READ_ONLY,
                        requiresApproval = false
                    )
                }
                "npm version" -> {
                    return PlannedAction(
                        action = CommandAction.TERMUX_COMMAND,
                        category = CommandCategory.DEVELOPMENT,
                        rawArguments = "npm --version",
                        riskLevel = TermuxRiskLevel.READ_ONLY,
                        requiresApproval = false
                    )
                }
                "python version" -> {
                    return PlannedAction(
                        action = CommandAction.TERMUX_COMMAND,
                        category = CommandCategory.DEVELOPMENT,
                        rawArguments = "python --version",
                        riskLevel = TermuxRiskLevel.READ_ONLY,
                        requiresApproval = false
                    )
                }
                "git status" -> {
                    return PlannedAction(
                        action = CommandAction.TERMUX_COMMAND,
                        category = CommandCategory.DEVELOPMENT,
                        rawArguments = "git status",
                        riskLevel = TermuxRiskLevel.READ_ONLY,
                        requiresApproval = false
                    )
                }
                else -> {
                    // Reuse ToolCommandMatcher for voice tool fuzzy matching
                    val matchOutcome = toolMatcher.matchSingleTarget(target)
                    if (matchOutcome is ToolMatchOutcome.Success) {
                        val tool = matchOutcome.result.tool
                        if (tool.id == "termux") {
                            return PlannedAction(
                                action = CommandAction.TERMUX_COMMAND,
                                category = CommandCategory.DEVELOPMENT,
                                rawArguments = "whoami",
                                riskLevel = TermuxRiskLevel.READ_ONLY,
                                requiresApproval = false
                            )
                        } else if (tool.id == "github") {
                            return PlannedAction(
                                action = CommandAction.CHECK_GITHUB,
                                category = CommandCategory.DEVELOPMENT,
                                targetAppOrPerson = "github",
                                requiresApproval = false
                            )
                        } else {
                            return PlannedAction(
                                action = CommandAction.OPEN_APP,
                                category = CommandCategory.DEVICE_ACTION,
                                targetAppOrPerson = tool.name,
                                requiresApproval = false
                            )
                        }
                    }
                }
            }
        }

        if (lower.startsWith("termux ")) {
            val cmd = trimmed.substring(7).trim()
            val risk = TermuxCommandClassifier.classifyCommandLine(cmd)
            val requiresApproval = TermuxCommandClassifier.requiresApproval(risk)
            val proposal = if (requiresApproval) {
                CommandProposal(
                    tool = "Termux",
                    workspace = "Active Workspace",
                    command = cmd,
                    riskLevel = risk,
                    reason = "Explicit shell command execution"
                )
            } else null
            return PlannedAction(
                action = CommandAction.TERMUX_COMMAND,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = cmd,
                riskLevel = risk,
                requiresApproval = requiresApproval,
                proposal = proposal
            )
        }

        // Direct command strings: git, gradle, npm
        if (lower.startsWith("git ") || lower.startsWith("npm ") || lower.startsWith("gradle ") || lower.startsWith("./gradlew ")) {
            val risk = TermuxCommandClassifier.classifyCommandLine(trimmed)
            val requiresApproval = TermuxCommandClassifier.requiresApproval(risk)
            val proposal = if (requiresApproval) {
                CommandProposal(
                    tool = "Termux",
                    workspace = "Active Workspace",
                    command = trimmed,
                    riskLevel = risk,
                    reason = "Direct development tool execution"
                )
            } else null
            return PlannedAction(
                action = CommandAction.TERMUX_COMMAND,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed,
                riskLevel = risk,
                requiresApproval = requiresApproval,
                proposal = proposal
            )
        }

        if (lower == "run tests" || lower == "run test") {
            val proposal = CommandProposal(
                tool = "Termux",
                workspace = "Active Workspace",
                command = "test",
                riskLevel = TermuxRiskLevel.MUTATING,
                reason = "Run test suite for project"
            )
            return PlannedAction(
                action = CommandAction.TERMUX_COMMAND,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = "test",
                riskLevel = TermuxRiskLevel.MUTATING,
                requiresApproval = true,
                proposal = proposal
            )
        }

        if (lower == "build project" || lower == "build app") {
            val proposal = CommandProposal(
                tool = "Termux",
                workspace = "Active Workspace",
                command = "build",
                riskLevel = TermuxRiskLevel.MUTATING,
                reason = "Build project artifacts"
            )
            return PlannedAction(
                action = CommandAction.TERMUX_COMMAND,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = "build",
                riskLevel = TermuxRiskLevel.MUTATING,
                requiresApproval = true,
                proposal = proposal
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
            val arg = trimmed.substring(6).trim()
            val proposal = CommandProposal(
                tool = "Termux",
                workspace = "Active Workspace",
                command = "build $arg",
                riskLevel = TermuxRiskLevel.MUTATING,
                reason = "Build command: $arg"
            )
            return PlannedAction(
                action = CommandAction.BUILD,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = arg,
                riskLevel = TermuxRiskLevel.MUTATING,
                requiresApproval = true,
                proposal = proposal
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

        if (lower.startsWith("push ") || lower == "push" || lower == "push code") {
            val proposal = CommandProposal(
                tool = "Termux",
                workspace = "Active Workspace",
                command = "git push",
                riskLevel = TermuxRiskLevel.PUBLISHING,
                reason = "Publish commits to git remote"
            )
            return PlannedAction(
                action = CommandAction.PUSH,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = trimmed.removePrefix("push").removePrefix("code").trim().ifBlank { null },
                riskLevel = TermuxRiskLevel.PUBLISHING,
                requiresApproval = true,
                proposal = proposal
            )
        }

        if (lower.startsWith("delete ")) {
            val arg = trimmed.substring(7).trim()
            val proposal = CommandProposal(
                tool = "Termux",
                workspace = "Active Workspace",
                command = "rm -rf $arg",
                riskLevel = TermuxRiskLevel.DESTRUCTIVE,
                reason = "Permanently delete files or directories"
            )
            return PlannedAction(
                action = CommandAction.DELETE,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = arg,
                riskLevel = TermuxRiskLevel.DESTRUCTIVE,
                requiresApproval = true,
                proposal = proposal
            )
        }

        if (lower.startsWith("overwrite ")) {
            val arg = trimmed.substring(10).trim()
            val proposal = CommandProposal(
                tool = "Termux",
                workspace = "Active Workspace",
                command = "overwrite $arg",
                riskLevel = TermuxRiskLevel.DESTRUCTIVE,
                reason = "Overwrite existing content"
            )
            return PlannedAction(
                action = CommandAction.OVERWRITE,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = arg,
                riskLevel = TermuxRiskLevel.DESTRUCTIVE,
                requiresApproval = true,
                proposal = proposal
            )
        }

        if (lower.startsWith("run ")) {
            val arg = trimmed.substring(4).trim()
            val risk = TermuxCommandClassifier.classifyCommandLine(arg)
            val requiresApproval = TermuxCommandClassifier.requiresApproval(risk)
            val proposal = if (requiresApproval) {
                CommandProposal(
                    tool = "Termux",
                    workspace = "Active Workspace",
                    command = arg,
                    riskLevel = risk,
                    reason = "Run command: $arg"
                )
            } else null
            return PlannedAction(
                action = CommandAction.RUN_COMMAND,
                category = CommandCategory.DEVELOPMENT,
                rawArguments = arg,
                riskLevel = risk,
                requiresApproval = requiresApproval,
                proposal = proposal
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
