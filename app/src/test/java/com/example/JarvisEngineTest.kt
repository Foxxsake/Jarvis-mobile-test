package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvisEngineTest {

    private lateinit var context: Context
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var parser: CommandParser

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        toolRegistry = ToolRegistry(context)
        toolExecutor = ToolExecutor(context, toolRegistry)
        parser = CommandParser()
    }

    @Test
    fun `open GitHub parsing`() {
        val parsed = parser.parse("open GitHub")
        assertEquals(CommandAction.OPEN_APP, parsed.action)
        assertEquals(CommandCategory.DEVICE_ACTION, parsed.category)
        assertEquals("GitHub", parsed.targetAppOrPerson)
        assertFalse(parsed.requiresApproval)
    }

    @Test
    fun `open Pydroid alias resolution`() {
        val tool = toolRegistry.findTool("pydroid")
        assertNotNull(tool)
        assertEquals("pydroid", tool?.id)
        assertEquals("Pydroid 3", tool?.name)
    }

    @Test
    fun `multi-word CALL target preservation`() {
        val parsed = parser.parse("call John Smith")
        assertEquals(CommandAction.CALL, parsed.action)
        assertEquals("John Smith", parsed.targetAppOrPerson)
        assertTrue(parsed.requiresApproval)
    }

    @Test
    fun `text command without colon does not fake contact boundaries`() {
        val parsed = parser.parse("text John Smith I'm running late")
        assertEquals(CommandAction.TEXT, parsed.action)
        assertNull(parsed.targetAppOrPerson)
        assertNull(parsed.messageOrQuery)
        assertEquals("John Smith I'm running late", parsed.rawArguments)
        assertTrue(parsed.requiresApproval)
    }

    @Test
    fun `explicit colon-delimited communication parsing`() {
        val parsed = parser.parse("text John Smith: I'm running late")
        assertEquals(CommandAction.TEXT, parsed.action)
        assertEquals("John Smith", parsed.targetAppOrPerson)
        assertEquals("I'm running late", parsed.messageOrQuery)
        assertTrue(parsed.requiresApproval)
    }

    @Test
    fun `unresolved communication command returns CONTACT_RESOLUTION_REQUIRED`() {
        val parsed = parser.parse("text John Smith I'm running late")
        val result = toolExecutor.executeAction(parsed)
        assertEquals(ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED, result.status)
        assertTrue(result.message.contains("Contact resolution required"))
    }

    @Test
    fun `safe app launch does not require approval`() {
        val parsed = parser.parse("open github")
        assertFalse(parsed.requiresApproval)
    }

    @Test
    fun `check GitHub does not require approval`() {
        val parsed = parser.parse("check github")
        assertEquals(CommandAction.CHECK_GITHUB, parsed.action)
        assertFalse(parsed.requiresApproval)
    }

    @Test
    fun `push requires approval`() {
        val parsed = parser.parse("push changes")
        assertEquals(CommandAction.PUSH, parsed.action)
        assertTrue(parsed.requiresApproval)
    }

    @Test
    fun `delete requires approval`() {
        val parsed = parser.parse("delete file.kt")
        assertEquals(CommandAction.DELETE, parsed.action)
        assertTrue(parsed.requiresApproval)
    }

    @Test
    fun `overwrite requires approval`() {
        val parsed = parser.parse("overwrite main.kt")
        assertEquals(CommandAction.OVERWRITE, parsed.action)
        assertTrue(parsed.requiresApproval)
    }

    @Test
    fun `destructive run command requires approval`() {
        val parsed = parser.parse("run rm -rf .")
        assertEquals(CommandAction.RUN_COMMAND, parsed.action)
        assertTrue(parsed.requiresApproval)
    }

    @Test
    fun `unknown command handling`() {
        val parsed = parser.parse("do something completely unhandled")
        assertEquals(CommandAction.UNKNOWN, parsed.action)
        assertEquals(CommandCategory.UNKNOWN, parsed.category)
        assertFalse(parsed.requiresApproval)
    }

    @Test
    fun `tool alias resolution for multiple tools`() {
        val spck = toolRegistry.findTool("spck editor")
        assertNotNull(spck)
        assertEquals("SPCK", spck?.name)

        val aiStudio = toolRegistry.findTool("ai studio")
        assertNotNull(aiStudio)
        assertEquals("Google AI Studio", aiStudio?.name)

        val codeStudio = toolRegistry.findTool("code studio")
        assertNotNull(codeStudio)
        assertEquals("Code Studio", codeStudio?.name)
    }

    @Test
    fun `unavailable tool detection`() {
        val parsed = parser.parse("open nonExistentApp")
        val result = toolExecutor.executeAction(parsed)
        assertEquals(ToolExecutionStatus.NOT_INSTALLED, result.status)
        assertTrue(result.message.contains("not registered or installed"))
    }

    @Test
    fun `disabled tool exclusion`() {
        toolRegistry.updateDisabledTools(setOf("pydroid"))
        val pydroidTool = toolRegistry.findTool("pydroid")
        assertNotNull(pydroidTool)
        assertFalse(pydroidTool!!.enabled)

        val parsed = parser.parse("open pydroid")
        val result = toolExecutor.executeAction(parsed)
        assertEquals(ToolExecutionStatus.FAILED, result.status)
        assertTrue(result.message.contains("disabled in settings"))
    }

    @Test
    fun `development placeholder returns NOT_IMPLEMENTED`() {
        val parsed = parser.parse("build a PWA")
        val result = toolExecutor.executeAction(parsed)
        assertEquals(ToolExecutionStatus.NOT_IMPLEMENTED, result.status)
        assertTrue(result.message.contains("not yet implemented"))
    }

    @Test
    fun `failed execution is not logged as success`() {
        val parsed = parser.parse("do something strange")
        val result = toolExecutor.executeAction(parsed)
        assertNotEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals(ToolExecutionStatus.NOT_IMPLEMENTED, result.status)
    }

    @Test
    fun `ToolExecutionResult message is persisted and descriptive`() {
        val parsed = parser.parse("open nonExistentTool")
        val result = toolExecutor.executeAction(parsed)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `local processing disabled behaviour`() {
        val parsed = parser.parse("open github")
        val result = toolExecutor.executeAction(parsed, isLocalProcessingEnabled = false)
        assertEquals(ToolExecutionStatus.FAILED, result.status)
        assertEquals("Local command processing is currently disabled in settings.", result.message)
    }
}
