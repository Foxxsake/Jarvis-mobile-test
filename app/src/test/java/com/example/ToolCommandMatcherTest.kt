package com.example

import com.example.data.AccessPolicy
import com.example.engine.*
import org.junit.Assert.*
import org.junit.Test

class ToolCommandMatcherTest {

    private val tools = listOf(
        Tool(
            id = "calc_basic",
            name = "Basic Calculator",
            description = "Standard math calculator",
            capabilities = listOf("math"),
            preferredUses = "Calculations",
            toolType = ToolType.APP,
            packageNames = listOf("com.google.android.calculator"),
            installedPackageName = "com.google.android.calculator",
            aliases = listOf("calculator", "calc"),
            policy = AccessPolicy.ALLOW,
            source = "DISCOVERED"
        ),
        Tool(
            id = "calc_scientific",
            name = "Scientific Calculator",
            description = "Advanced scientific calculator",
            capabilities = listOf("math", "science"),
            preferredUses = "Scientific math",
            toolType = ToolType.APP,
            packageNames = listOf("com.scientific.calculator"),
            installedPackageName = "com.scientific.calculator",
            aliases = listOf("calculator", "scicalc"),
            policy = AccessPolicy.ALLOW,
            source = "DISCOVERED"
        ),
        Tool(
            id = "spotify",
            name = "Spotify",
            description = "Music streaming",
            capabilities = listOf("music"),
            preferredUses = "Music",
            toolType = ToolType.APP,
            packageNames = listOf("com.spotify.music"),
            installedPackageName = "com.spotify.music",
            aliases = emptyList(),
            policy = AccessPolicy.ALLOW,
            source = "DISCOVERED"
        )
    )

    @Test
    fun `findToolOutcome returns Success when single tool matches exactly`() {
        val matcher = ToolCommandMatcher { tools }
        val outcome = matcher.match("open spotify")
        assertTrue(outcome is ToolMatchOutcome.Success)
        val success = outcome as ToolMatchOutcome.Success
        assertEquals("Spotify", success.result.tool.name)
        assertEquals("Spotify", success.result.matchedTerm)
    }

    @Test
    fun `findToolOutcome returns Ambiguous when multiple tools match query`() {
        val matcher = ToolCommandMatcher { tools }
        val outcome = matcher.match("open calculator")
        assertTrue("Expected Ambiguous outcome but was $outcome", outcome is ToolMatchOutcome.Ambiguous)
        val ambiguous = outcome as ToolMatchOutcome.Ambiguous
        assertEquals(2, ambiguous.candidateTools.size)
        assertTrue(ambiguous.candidateTools.any { it.name == "Basic Calculator" })
        assertTrue(ambiguous.candidateTools.any { it.name == "Scientific Calculator" })
    }

    @Test
    fun `findToolOutcome returns NoMatch when no tool matches query`() {
        val matcher = ToolCommandMatcher { tools }
        val outcome = matcher.match("open nonexistenttoolxyz")
        assertTrue(outcome is ToolMatchOutcome.NoMatch)
    }

    @Test
    fun `blocked tools are excluded from match outcome`() {
        val toolsWithBlocked = tools.map {
            if (it.id == "calc_scientific") it.copy(policy = AccessPolicy.BLOCK, enabled = false) else it
        }
        val matcher = ToolCommandMatcher { toolsWithBlocked }
        val outcome = matcher.match("open calculator")
        assertTrue(outcome is ToolMatchOutcome.Success)
        val success = outcome as ToolMatchOutcome.Success
        assertEquals("Basic Calculator", success.result.tool.name)
    }
}
