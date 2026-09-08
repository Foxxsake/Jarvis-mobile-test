package com.example.engine.voice

import com.example.data.AccessPolicy
import com.example.engine.CommandAction
import com.example.engine.CommandPlan
import com.example.engine.ToolExecutionResult
import com.example.engine.ToolExecutionStatus

object JarvisSpeechFormatter {

    fun formatApprovalPrompt(plan: CommandPlan, planDetails: String?): String {
        val action = plan.actions.firstOrNull() ?: return "Shall I continue?"
        return when (action.action) {
            CommandAction.CALL -> {
                val target = action.targetAppOrPerson?.ifBlank { null } ?: "contact"
                "Ready to call $target. Shall I continue?"
            }
            CommandAction.TEXT -> {
                val target = action.targetAppOrPerson?.ifBlank { null } ?: "contact"
                "Ready to send a message to $target. Shall I continue?"
            }
            CommandAction.EMAIL -> {
                val target = action.targetAppOrPerson?.ifBlank { null } ?: "contact"
                "Ready to send email to $target. Shall I continue?"
            }
            CommandAction.OPEN_APP -> {
                val app = action.targetAppOrPerson ?: "app"
                "Open $app? Please confirm."
            }
            CommandAction.DELETE, CommandAction.OVERWRITE -> {
                "Destructive action requested. Confirmation required. Shall I continue?"
            }
            CommandAction.PUSH -> {
                "Ready to push code to git remote. Shall I continue?"
            }
            CommandAction.TERMUX_COMMAND -> {
                "Terminal command requires approval. Shall I execute it?"
            }
            else -> {
                "Action ${action.action.name} requires your approval. Shall I continue?"
            }
        }
    }

    fun formatExecutionResult(commandText: String, result: ToolExecutionResult): String {
        return when (result.status) {
            ToolExecutionStatus.SUCCESS -> {
                // Short human-friendly responses instead of raw dumps
                if (result.message.startsWith("Opened Android Settings")) {
                    "Opened Settings."
                } else if (result.message.startsWith("Opening ") || result.message.startsWith("Launched ")) {
                    result.message.substringBefore(" (").trim()
                } else if (result.message.startsWith("Pushed code")) {
                    "Pushed code to git remote successfully."
                } else if (result.message.startsWith("Project [")) {
                    "Project status checked."
                } else if (result.message.contains("Termux command executed successfully")) {
                    "Command finished."
                } else {
                    // Fallback to first line, max 80 chars
                    result.message.lineSequence().firstOrNull()?.take(80)?.trim() ?: "Action completed."
                }
            }
            ToolExecutionStatus.NOT_INSTALLED -> {
                "I can't find that app."
            }
            ToolExecutionStatus.AMBIGUOUS_APP -> {
                val appName = result.candidateTools.firstOrNull()?.name ?: "app"
                "Multiple matching apps found. Please choose on screen."
            }
            ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED -> {
                if (result.message.contains("not found", ignoreCase = true)) {
                    "I couldn't find that contact."
                } else {
                    "Please choose contact destination on screen."
                }
            }
            ToolExecutionStatus.PERMISSION_REQUIRED -> {
                "Permission required. Please grant access on screen."
            }
            ToolExecutionStatus.SETUP_REQUIRED -> {
                "Setup required in settings."
            }
            ToolExecutionStatus.TIMED_OUT -> {
                "The command timed out."
            }
            ToolExecutionStatus.WORKSPACE_REQUIRED -> {
                "Workspace directory required. Please configure it in settings."
            }
            ToolExecutionStatus.COMMAND_REJECTED -> {
                "Command was cancelled."
            }
            ToolExecutionStatus.NOT_IMPLEMENTED -> {
                "That feature is not yet implemented."
            }
            ToolExecutionStatus.UNSUPPORTED, ToolExecutionStatus.NOT_SUPPORTED -> {
                "That action is not supported on this device."
            }
            ToolExecutionStatus.REQUIRES_CONNECTION -> {
                "Connection required."
            }
            ToolExecutionStatus.SKIPPED -> {
                "Action was skipped."
            }
            ToolExecutionStatus.FAILED -> {
                if (result.message.contains("blocked in JARVIS settings", ignoreCase = true)) {
                    val appName = result.message.substringAfter("Cannot open ").substringBefore(" because it is blocked").trim()
                    if (appName.isNotBlank() && !appName.startsWith("Cannot open")) {
                        "$appName is blocked in JARVIS settings."
                    } else {
                        "This app is blocked in JARVIS settings."
                    }
                } else {
                    "Sorry, that action failed."
                }
            }
        }
    }
}
