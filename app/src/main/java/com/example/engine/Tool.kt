package com.example.engine

import com.example.data.AccessPolicy

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
    val packageNames: List<String> = emptyList(),
    val installedPackageName: String? = null,
    val url: String? = null,
    val aliases: List<String> = emptyList(),
    val installedOrAvailable: Boolean = false,
    val enabled: Boolean = true,
    val policy: AccessPolicy = AccessPolicy.ASK_EACH_TIME,
    val source: String = "STANDARD" // "STANDARD" or "DISCOVERED"
)
