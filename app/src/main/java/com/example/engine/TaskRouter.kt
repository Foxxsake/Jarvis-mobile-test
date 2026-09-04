package com.example.engine

data class ExecutionPlan(
    val steps: List<String>,
    val primaryToolId: String?
)

class TaskRouter(private val toolRegistry: ToolRegistry) {
    fun route(command: ParsedCommand): ExecutionPlan {
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

            CommandAction.BUILD,
            CommandAction.WORK_ON,
            CommandAction.PUSH,
            CommandAction.DELETE,
            CommandAction.OVERWRITE,
            CommandAction.RUN_COMMAND -> ExecutionPlan(
                steps = listOf(
                    "Development action: ${command.action.name}",
                    "Work from GitHub repository",
                    "Use Termux for local build/testing",
                    "Open Acode for manual code inspection"
                ),
                primaryToolId = "termux"
            )

            CommandAction.UNKNOWN -> ExecutionPlan(
                steps = listOf("AI planning will be required later. Command not understood natively."),
                primaryToolId = null
            )
        }
    }
}
