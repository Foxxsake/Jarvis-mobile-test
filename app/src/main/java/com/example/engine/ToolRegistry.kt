package com.example.engine

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ToolRegistry(private val context: Context) {
    private val _tools = MutableStateFlow<List<Tool>>(emptyList())
    val tools: StateFlow<List<Tool>> = _tools.asStateFlow()

    private var disabledIds: Set<String> = emptySet()

    init {
        refreshTools()
    }

    fun updateDisabledTools(disabledToolIds: Set<String>) {
        this.disabledIds = disabledToolIds
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
                packageNames = listOf("com.github.android"),
                aliases = listOf("github", "gh")
            ),
            Tool(
                id = "termux",
                name = "Termux",
                description = "Commands, Git, Node/npm, Build/test, Automation",
                capabilities = listOf("shell", "cli", "linux"),
                preferredUses = "Local builds and automation",
                toolType = ToolType.APP,
                packageNames = listOf("com.termux"),
                aliases = listOf("termux", "terminal", "shell")
            ),
            Tool(
                id = "acode",
                name = "Acode",
                description = "Code editing, Project viewing",
                capabilities = listOf("editor", "text"),
                preferredUses = "Manual code inspection and fast edits",
                toolType = ToolType.APP,
                packageNames = listOf("com.foxdebug.acodefree", "com.foxdebug.acode"),
                aliases = listOf("acode")
            ),
            Tool(
                id = "spck",
                name = "SPCK",
                description = "Code editing",
                capabilities = listOf("editor", "web"),
                preferredUses = "Web development",
                toolType = ToolType.APP,
                packageNames = listOf("io.spck"),
                aliases = listOf("spck", "spck editor")
            ),
            Tool(
                id = "code_studio",
                name = "Code Studio",
                description = "Code editing",
                capabilities = listOf("editor", "ide"),
                preferredUses = "Full IDE experience",
                toolType = ToolType.APP,
                packageNames = listOf("com.alif.ide"),
                aliases = listOf("code studio")
            ),
            Tool(
                id = "pydroid",
                name = "Pydroid 3",
                description = "Python execution",
                capabilities = listOf("python", "repl"),
                preferredUses = "Running python scripts",
                toolType = ToolType.APP,
                packageNames = listOf("ru.iiec.pydroid3"),
                aliases = listOf("pydroid", "pydroid 3", "python")
            ),
            Tool(
                id = "expo_go",
                name = "Expo Go",
                description = "React Native preview",
                capabilities = listOf("react-native", "preview"),
                preferredUses = "Previewing mobile apps",
                toolType = ToolType.APP,
                packageNames = listOf("host.exp.exponent"),
                aliases = listOf("expo", "expo go")
            ),
            Tool(
                id = "google_ai_studio",
                name = "Google AI Studio",
                description = "AI-assisted application development",
                capabilities = listOf("ai", "generation"),
                preferredUses = "Generating boilerplate and AI logic",
                toolType = ToolType.WEB,
                url = "https://aistudio.google.com/",
                aliases = listOf("ai studio", "google ai studio"),
                installedOrAvailable = true
            )
        )

        val updatedTools = baseTools.map { tool ->
            val isEnabled = !disabledIds.contains(tool.id)
            if (tool.toolType == ToolType.APP && tool.packageNames.isNotEmpty()) {
                val detectedPackage = findInstalledPackage(tool.packageNames)
                tool.copy(
                    installedOrAvailable = detectedPackage != null,
                    installedPackageName = detectedPackage,
                    enabled = isEnabled
                )
            } else {
                tool.copy(enabled = isEnabled)
            }
        }
        _tools.value = updatedTools
    }

    private fun findInstalledPackage(packageNames: List<String>): String? {
        val pm = try { context.packageManager } catch (e: Exception) { null } ?: return null
        for (pkg in packageNames) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun findTool(query: String): Tool? {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return null

        val currentList = _tools.value
        // 1. Match by ID
        currentList.find { it.id.lowercase() == clean }?.let { return it }

        // 2. Match by Name
        currentList.find { it.name.lowercase() == clean }?.let { return it }

        // 3. Match by Alias
        currentList.find { tool -> tool.aliases.any { alias -> alias.lowercase() == clean } }?.let { return it }

        // 4. Match substring in Name or Alias
        return currentList.find { tool ->
            tool.name.lowercase().contains(clean) || tool.aliases.any { alias -> alias.lowercase().contains(clean) }
        }
    }
}
