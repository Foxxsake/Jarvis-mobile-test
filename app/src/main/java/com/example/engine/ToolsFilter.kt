package com.example.engine

object ToolsFilter {
    /**
     * Filters tools and apps based on search query.
     * Matches across:
     * - Tool / App name
     * - Tool ID
     * - Aliases (where present)
     * - Package names (declared and installed)
     * - Capabilities
     */
    fun filter(tools: List<Tool>, query: String): List<Tool> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return tools

        val q = trimmed.lowercase()
        return tools.filter { tool ->
            tool.name.lowercase().contains(q) ||
            tool.id.lowercase().contains(q) ||
            tool.aliases.any { it.lowercase().contains(q) } ||
            tool.packageNames.any { it.lowercase().contains(q) } ||
            (tool.installedPackageName != null && tool.installedPackageName.lowercase().contains(q)) ||
            tool.capabilities.any { it.lowercase().contains(q) }
        }
    }
}
