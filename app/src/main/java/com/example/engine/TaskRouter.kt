package com.example.engine

data class ExecutionPlan(
    val steps: List<String>,
    val primaryToolId: String?
)

class TaskRouter(private val toolRegistry: ToolRegistry) {
    fun route(plan: CommandPlan): ExecutionPlan {
        val steps = plan.actions.mapIndexed { index, action ->
            val stepDesc = routeSingle(action).steps.firstOrNull() ?: "Execute action"
            if (plan.actions.size == 1) stepDesc else "${index + 1}. $stepDesc"
        }
        val primaryToolId = plan.actions.firstOrNull()?.let { routeSingle(it).primaryToolId }
        
        return ExecutionPlan(
            steps = steps,
            primaryToolId = primaryToolId
        )
    }

    private fun routeSingle(command: PlannedAction): ExecutionPlan {
        return when (command.action) {
            CommandAction.OPEN_APP -> {
                val target = command.targetAppOrPerson ?: ""
                val tool = toolRegistry.findTool(target)
                if (tool != null) {
                    if (!tool.enabled) {
                        ExecutionPlan(
                            steps = listOf("Tool ${tool.name} is disabled in settings"),
                            primaryToolId = tool.id
                        )
                    } else {
                        ExecutionPlan(
                            steps = listOf("Launch ${tool.name}"),
                            primaryToolId = tool.id
                        )
                    }
                } else {
                    ExecutionPlan(
                        steps = listOf("Attempting to launch $target"),
                        primaryToolId = null
                    )
                }
            }

            CommandAction.OPEN_SETTINGS -> ExecutionPlan(
                steps = listOf("Open Android System Settings"),
                primaryToolId = null
            )

            CommandAction.CALL,
            CommandAction.TEXT,
            CommandAction.EMAIL -> ExecutionPlan(
                steps = listOf("Resolve contact and prepare communication intent"),
                primaryToolId = null
            )

            CommandAction.CHECK_GITHUB -> ExecutionPlan(
                steps = listOf("Check GitHub status / launch GitHub app"),
                primaryToolId = "github"
            )

            CommandAction.CHECK_PROJECT_STATUS -> ExecutionPlan(
                steps = listOf("Check project status in workspace via Termux"),
                primaryToolId = "termux"
            )

            CommandAction.TERMUX_COMMAND -> ExecutionPlan(
                steps = listOf("Execute Termux command: ${command.rawArguments ?: "command"}" + if (command.requiresApproval) " [REQUIRES APPROVAL]" else ""),
                primaryToolId = "termux"
            )

            CommandAction.BUILD -> ExecutionPlan(
                steps = listOf("Build project" + if (command.requiresApproval) " [REQUIRES APPROVAL]" else ""),
                primaryToolId = "termux"
            )
            
            CommandAction.WORK_ON -> ExecutionPlan(
                steps = listOf("Work on project: ${command.rawArguments ?: ""}" + if (command.requiresApproval) " [REQUIRES APPROVAL]" else ""),
                primaryToolId = "termux"
            )
            
            CommandAction.PUSH -> ExecutionPlan(
                steps = listOf("Push code to repository" + if (command.requiresApproval) " [REQUIRES APPROVAL]" else ""),
                primaryToolId = "termux"
            )
            
            CommandAction.DELETE -> ExecutionPlan(
                steps = listOf("Delete: ${command.rawArguments ?: ""}" + if (command.requiresApproval) " [REQUIRES APPROVAL]" else ""),
                primaryToolId = "termux"
            )
            
            CommandAction.OVERWRITE -> ExecutionPlan(
                steps = listOf("Overwrite file" + if (command.requiresApproval) " [REQUIRES APPROVAL]" else ""),
                primaryToolId = "termux"
            )
            
            CommandAction.RUN_COMMAND -> ExecutionPlan(
                steps = listOf("Run command" + if (command.requiresApproval) " [REQUIRES APPROVAL]" else ""),
                primaryToolId = "termux"
            )

            // Phone control actions
            CommandAction.SET_VOLUME -> {
                val arg = command.rawArguments ?: ""
                val desc = when (arg) {
                    "up" -> "Increase volume"
                    "down" -> "Decrease volume"
                    "mute" -> "Mute audio"
                    "unmute" -> "Unmute audio"
                    else -> "Set volume to $arg%"
                }
                ExecutionPlan(steps = listOf(desc), primaryToolId = null)
            }

            CommandAction.SET_BRIGHTNESS -> {
                val arg = command.rawArguments ?: ""
                val desc = when (arg) {
                    "up" -> "Increase brightness"
                    "down" -> "Decrease brightness"
                    else -> "Set brightness to $arg%"
                }
                ExecutionPlan(steps = listOf(desc), primaryToolId = null)
            }

            CommandAction.TOGGLE_WIFI -> {
                val arg = command.rawArguments ?: "toggle"
                ExecutionPlan(steps = listOf("Toggle WiFi ($arg)"), primaryToolId = null)
            }

            CommandAction.TOGGLE_BLUETOOTH -> {
                val arg = command.rawArguments ?: "toggle"
                ExecutionPlan(steps = listOf("Toggle Bluetooth ($arg)"), primaryToolId = null)
            }

            CommandAction.TOGGLE_FLASHLIGHT -> {
                val arg = command.rawArguments ?: "toggle"
                ExecutionPlan(steps = listOf("Toggle flashlight ($arg)"), primaryToolId = null)
            }

            CommandAction.PLAY_MEDIA -> ExecutionPlan(
                steps = listOf("Play/resume media"),
                primaryToolId = null
            )

            CommandAction.PAUSE_MEDIA -> ExecutionPlan(
                steps = listOf("Pause media"),
                primaryToolId = null
            )

            CommandAction.NEXT_TRACK -> ExecutionPlan(
                steps = listOf("Skip to next track"),
                primaryToolId = null
            )

            CommandAction.PREV_TRACK -> ExecutionPlan(
                steps = listOf("Go to previous track"),
                primaryToolId = null
            )

            CommandAction.SET_ALARM -> ExecutionPlan(
                steps = listOf("Set alarm for ${command.rawArguments ?: "unknown time"}"),
                primaryToolId = null
            )

            CommandAction.SET_TIMER -> {
                val seconds = command.rawArguments?.toIntOrNull() ?: 0
                val mins = seconds / 60
                val secs = seconds % 60
                val timeStr = when {
                    mins > 0 && secs > 0 -> "${mins}m ${secs}s"
                    mins > 0 -> "${mins}m"
                    else -> "${secs}s"
                }
                ExecutionPlan(steps = listOf("Set timer for $timeStr"), primaryToolId = null)
            }

            CommandAction.SCREENSHOT -> ExecutionPlan(
                steps = listOf("Take screenshot"),
                primaryToolId = null
            )

            CommandAction.OPEN_URL -> ExecutionPlan(
                steps = listOf("Open URL: ${command.rawArguments ?: "unknown URL"}"),
                primaryToolId = null
            )

            CommandAction.SEARCH_WEB -> ExecutionPlan(
                steps = listOf("Web search: ${command.rawArguments ?: "unknown query"}"),
                primaryToolId = null
            )

            CommandAction.TAKE_PHOTO -> ExecutionPlan(
                steps = listOf("Open camera to take photo"),
                primaryToolId = null
            )

            CommandAction.DO_NOT_DISTURB_ON -> ExecutionPlan(
                steps = listOf("Enable Do Not Disturb"),
                primaryToolId = null
            )

            CommandAction.DO_NOT_DISTURB_OFF -> ExecutionPlan(
                steps = listOf("Disable Do Not Disturb"),
                primaryToolId = null
            )

            CommandAction.UNKNOWN -> ExecutionPlan(
                steps = listOf("AI planning required. (Command not understood natively)"),
                primaryToolId = null
            )
        }
    }
}
