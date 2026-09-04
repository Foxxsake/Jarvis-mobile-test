package com.example

import com.example.engine.CommandCategory
import com.example.engine.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private val parser = CommandParser()

    @Test
    fun `open command parses correctly`() {
        val result = parser.parse("Open GitHub")
        assertEquals(CommandCategory.DEVICE_ACTION, result.category)
        assertEquals("github", result.targetAppOrPerson)
        assertFalse(result.requiresApproval)
    }

    @Test
    fun `call command parses correctly and requires approval`() {
        val result = parser.parse("call John Doe")
        assertEquals(CommandCategory.COMMUNICATION, result.category)
        assertEquals("john doe", result.targetAppOrPerson)
        assertTrue(result.requiresApproval)
    }

    @Test
    fun `text command parses correctly`() {
        val result = parser.parse("text Alice Hello there")
        assertEquals(CommandCategory.COMMUNICATION, result.category)
        assertEquals("alice", result.targetAppOrPerson)
        assertEquals("Hello there", result.messageOrQuery)
        assertTrue(result.requiresApproval)
    }

    @Test
    fun `build command parses correctly`() {
        val result = parser.parse("build a PWA")
        assertEquals(CommandCategory.DEVELOPMENT, result.category)
        assertTrue(result.requiresApproval)
    }

    @Test
    fun `unknown command falls back`() {
        val result = parser.parse("do something crazy")
        assertEquals(CommandCategory.UNKNOWN, result.category)
        assertFalse(result.requiresApproval)
    }
}
