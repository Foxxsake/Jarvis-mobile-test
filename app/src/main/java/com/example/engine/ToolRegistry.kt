package com.example.engine

data class Tool(
    val id: String,
    val name: String,
    val description: String,
    val capabilities: List<String>,
    val preferredUses: String,
    val installedOrAvailable: Boolean,
    val enabled: Boolean
)

class ToolRegistry {
    fun getTools(): List<Tool> {
        return listOf(
            Tool(
                id = "github",
                name = "GitHub",
                description = "Repository storage, Version control, Issues/code collaboration",
                capabilities = listOf("git", "issues", "code review"),
                preferredUses = "Version control and project sharing",
                installedOrAvailable = true,
                enabled = true
            ),
            Tool(
                id = "termux",
                name = "Termux",
                description = "Commands, Git, Node/npm, Build/test, Automation",
                capabilities = listOf("shell", "cli", "linux"),
                preferredUses = "Local builds and automation",
                installedOrAvailable = true,
                enabled = true
            ),
            Tool(
                id = "acode",
                name = "Acode",
                description = "Code editing, Project viewing",
                capabilities = listOf("editor", "text"),
                preferredUses = "Manual code inspection and fast edits",
                installedOrAvailable = true,
                enabled = true
            ),
            Tool(
                id = "spck",
                name = "SPCK",
                description = "Code editing",
                capabilities = listOf("editor", "web"),
                preferredUses = "Web development",
                installedOrAvailable = true,
                enabled = true
            ),
            Tool(
                id = "code_studio",
                name = "Code Studio",
                description = "Code editing",
                capabilities = listOf("editor", "ide"),
                preferredUses = "Full IDE experience",
                installedOrAvailable = true,
                enabled = true
            ),
            Tool(
                id = "pydroid",
                name = "Pydroid",
                description = "Python execution",
                capabilities = listOf("python", "repl"),
                preferredUses = "Running python scripts",
                installedOrAvailable = true,
                enabled = true
            ),
            Tool(
                id = "expo_go",
                name = "Expo Go",
                description = "React Native preview",
                capabilities = listOf("react-native", "preview"),
                preferredUses = "Previewing mobile apps",
                installedOrAvailable = true,
                enabled = true
            ),
            Tool(
                id = "google_ai_studio",
                name = "Google AI Studio",
                description = "AI-assisted application development",
                capabilities = listOf("ai", "generation"),
                preferredUses = "Generating boilerplate and AI logic",
                installedOrAvailable = true,
                enabled = true
            )
        )
    }
}
