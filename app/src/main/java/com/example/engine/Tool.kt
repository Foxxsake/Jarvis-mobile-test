package com.example.engine

enum class ToolType {
    APP, WEB, VIRTUAL
}

data class Tool(
    val id: String,
    val name: String,
    val description: String,
    val capabilities: List<String>,
    val preferredUses: String,
    val toolType: ToolType,
    val packageName: String? = null,
    val url: String? = null,
    var installedOrAvailable: Boolean = false,
    val enabled: Boolean = true
)
