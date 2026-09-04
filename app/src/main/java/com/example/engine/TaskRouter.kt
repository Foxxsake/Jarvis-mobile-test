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

            CommandAction.UNKNOWN -> ExecutionPlan(
                steps = listOf("AI planning required. (Command not understood natively)"),
                primaryToolId = null
            )
        }
    }
}
