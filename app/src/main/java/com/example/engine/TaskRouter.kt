package com.example.engine

data class ExecutionPlan(
    val steps: List<String>,
    val primaryToolId: String?
)

class TaskRouter(private val toolRegistry: ToolRegistry) {
    fun route(command: ParsedCommand): ExecutionPlan {
        if (command.category == CommandCategory.DEVICE_ACTION) {
            val toolName = command.targetAppOrPerson?.lowercase() ?: ""
            val tool = toolRegistry.tools.value.find { it.name.lowercase() == toolName }
            if (tool != null) {
                return ExecutionPlan(
                    steps = listOf("Launch ${tool.name}"),
                    primaryToolId = tool.id
                )
            }
            return ExecutionPlan(
                steps = listOf("Attempting to launch ${command.targetAppOrPerson}"),
                primaryToolId = null
            )
        }

        if (command.category == CommandCategory.DEVELOPMENT) {
            return ExecutionPlan(
                steps = listOf(
                    "Use development workflow",
                    "Work from GitHub repository",
                    "Use Termux for local build/testing",
                    "Open Acode when manual code inspection is useful",
                    "Request approval before pushing changes"
                ),
                primaryToolId = "termux"
            )
        }
        
        if (command.category == CommandCategory.COMMUNICATION) {
            return ExecutionPlan(
                steps = listOf("Open Android Intent for communication"),
                primaryToolId = null
            )
        }

        return ExecutionPlan(
            steps = listOf("AI planning will be required later. Command not understood natively."),
            primaryToolId = null
        )
    }
}
