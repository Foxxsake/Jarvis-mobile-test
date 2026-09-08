package com.example

import com.example.data.AccessPolicy
import com.example.engine.Tool
import com.example.engine.ToolType
import com.example.engine.ToolsFilter
import org.junit.Assert.*
import org.junit.Test

class ToolsFilterTest {

    private val sampleTools = listOf(
        Tool(
            id = "github",
            name = "GitHub",
            description = "Repository storage and version control",
            capabilities = listOf("git", "issues", "code review"),
            preferredUses = "Version control",
            toolType = ToolType.APP,
            packageNames = listOf("com.github.android"),
            aliases = listOf("github", "gh"),
            policy = AccessPolicy.ALLOW,
            source = "STANDARD"
        ),
        Tool(
            id = "termux",
            name = "Termux",
            description = "Terminal and local linux CLI",
            capabilities = listOf("shell", "cli", "linux"),
            preferredUses = "Local builds and automation",
            toolType = ToolType.APP,
            packageNames = listOf("com.termux"),
            aliases = listOf("termux"),
            policy = AccessPolicy.ALLOW,
            source = "STANDARD"
        ),
        Tool(
            id = "spotify",
            name = "Spotify",
            description = "Music streaming service",
            capabilities = listOf("music", "audio", "streaming"),
            preferredUses = "Playing music",
            toolType = ToolType.APP,
            packageNames = listOf("com.spotify.music"),
            installedPackageName = "com.spotify.music",
            aliases = emptyList(),
            policy = AccessPolicy.ALLOW,
            source = "DISCOVERED"
        ),
        Tool(
            id = "whatsapp",
            name = "WhatsApp",
            description = "Messaging and voice calls",
            capabilities = listOf("messaging", "chat", "calls"),
            preferredUses = "Chatting with friends",
            toolType = ToolType.APP,
            packageNames = listOf("com.whatsapp"),
            installedPackageName = "com.whatsapp",
            aliases = emptyList(),
            policy = AccessPolicy.ASK_EACH_TIME,
            source = "DISCOVERED"
        ),
        Tool(
            id = "calculator_basic",
            name = "Calculator",
            description = "Basic math calculator",
            capabilities = listOf("calculator", "math"),
            preferredUses = "Doing calculations",
            toolType = ToolType.APP,
            packageNames = listOf("com.google.android.calculator"),
            installedPackageName = "com.google.android.calculator",
            aliases = listOf("calc"),
            policy = AccessPolicy.ALLOW,
            source = "DISCOVERED"
        )
    )

    @Test
    fun `empty or blank query returns full tool list`() {
        assertEquals(5, ToolsFilter.filter(sampleTools, "").size)
        assertEquals(5, ToolsFilter.filter(sampleTools, "   ").size)
    }

    @Test
    fun `search by exact name finds single tool`() {
        val result = ToolsFilter.filter(sampleTools, "Spotify")
        assertEquals(1, result.size)
        assertEquals("Spotify", result.first().name)
    }

    @Test
    fun `search is case-insensitive`() {
        val lower = ToolsFilter.filter(sampleTools, "whatsapp")
        val upper = ToolsFilter.filter(sampleTools, "WHATSAPP")
        val mixed = ToolsFilter.filter(sampleTools, "WhAtSaPp")

        assertEquals(1, lower.size)
        assertEquals(1, upper.size)
        assertEquals(1, mixed.size)
        assertEquals("WhatsApp", lower.first().name)
    }

    @Test
    fun `search by alias matches tool`() {
        val ghResult = ToolsFilter.filter(sampleTools, "gh")
        assertEquals(1, ghResult.size)
        assertEquals("GitHub", ghResult.first().name)

        val calcResult = ToolsFilter.filter(sampleTools, "calc")
        assertEquals(1, calcResult.size)
        assertEquals("Calculator", calcResult.first().name)
    }

    @Test
    fun `search by capability matches relevant tools`() {
        val gitResult = ToolsFilter.filter(sampleTools, "git")
        assertTrue(gitResult.any { it.name == "GitHub" })

        val shellResult = ToolsFilter.filter(sampleTools, "shell")
        assertTrue(shellResult.any { it.name == "Termux" })

        val audioResult = ToolsFilter.filter(sampleTools, "audio")
        assertTrue(audioResult.any { it.name == "Spotify" })
    }

    @Test
    fun `search with no match returns empty list`() {
        val result = ToolsFilter.filter(sampleTools, "NonExistentApp12345")
        assertTrue(result.isEmpty())
    }
}
