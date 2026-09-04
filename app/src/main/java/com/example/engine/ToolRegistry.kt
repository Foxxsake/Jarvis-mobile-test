package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ToolRegistry(private val context: Context) {
    private val _tools = MutableStateFlow<List<Tool>>(emptyList())
    val tools: StateFlow<List<Tool>> = _tools.asStateFlow()

    init {
        refreshTools()
    }

    fun refreshTools() {
        val baseTools = listOf(
            Tool(
                id = "github",
                name = "GitHub",
                description = "Repository storage, Version control, Issues/code collaboration",
                capabilities = listOf("git", "issues", "code review"),
                preferredUses = "Version control and project sharing",
                toolType = ToolType.APP,
                packageName = "com.github.android",
                enabled = true
            ),
            Tool(
                id = "termux",
                name = "Termux",
                description = "Commands, Git, Node/npm, Build/test, Automation",
                capabilities = listOf("shell", "cli", "linux"),
                preferredUses = "Local builds and automation",
                toolType = ToolType.APP,
                packageName = "com.termux",
                enabled = true
            ),
            Tool(
                id = "acode",
                name = "Acode",
                description = "Code editing, Project viewing",
                capabilities = listOf("editor", "text"),
                preferredUses = "Manual code inspection and fast edits",
                toolType = ToolType.APP,
                packageName = "com.foxdebug.acode",
                enabled = true
            ),
            Tool(
                id = "spck",
                name = "SPCK",
                description = "Code editing",
                capabilities = listOf("editor", "web"),
                preferredUses = "Web development",
                toolType = ToolType.APP,
                packageName = "io.spck",
                enabled = true
            ),
            Tool(
                id = "code_studio",
                name = "Code Studio",
                description = "Code editing",
                capabilities = listOf("editor", "ide"),
                preferredUses = "Full IDE experience",
                toolType = ToolType.APP,
                packageName = "com.qamar.ide",
                enabled = true
            ),
            Tool(
                id = "pydroid",
                name = "Pydroid 3",
                description = "Python execution",
                capabilities = listOf("python", "repl"),
                preferredUses = "Running python scripts",
                toolType = ToolType.APP,
                packageName = "ru.iiec.pydroid3",
                enabled = true
            ),
            Tool(
                id = "expo_go",
                name = "Expo Go",
                description = "React Native preview",
                capabilities = listOf("react-native", "preview"),
                preferredUses = "Previewing mobile apps",
                toolType = ToolType.APP,
                packageName = "host.exp.exponent",
                enabled = true
            ),
            Tool(
                id = "google_ai_studio",
                name = "Google AI Studio",
                description = "AI-assisted application development",
                capabilities = listOf("ai", "generation"),
                preferredUses = "Generating boilerplate and AI logic",
                toolType = ToolType.WEB,
                url = "https://aistudio.google.com/",
                enabled = true,
                installedOrAvailable = true // Web tools are generally available
            )
        )

        val pm = context.packageManager
        val updatedTools = baseTools.map { tool ->
            if (tool.toolType == ToolType.APP && tool.packageName != null) {
                val isInstalled = try {
                    pm.getPackageInfo(tool.packageName, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
                tool.copy(installedOrAvailable = isInstalled)
            } else {
                tool
            }
        }
        _tools.value = updatedTools
    }
}
