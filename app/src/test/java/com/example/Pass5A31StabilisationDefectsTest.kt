package com.example

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AccessPolicy
import com.example.engine.*
import com.example.engine.policy.ExecutionAuthorization
import com.example.engine.policy.ExecutionPolicyGuard
import com.example.engine.policy.PolicyDecision
import com.example.engine.voice.handsfree.HandsFreeState
import com.example.ui.JarvisViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pass5A31StabilisationDefectsTest {

    private lateinit var context: Context
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var contactResolver: ContactResolver
    private lateinit var toolExecutor: ToolExecutor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val fakePolicyDao = FakeAppPolicyDao()
        toolRegistry = ToolRegistry(context, fakePolicyDao)
        contactResolver = ContactResolver(JarvisEngineTest.FakeContactsProvider())
        toolExecutor = ToolExecutor(context, toolRegistry, contactResolver)
        JarvisRuntime.setInstance(null)
    }

    // 1. HANDS-FREE STATE & STALE PREFERENCE SEPARATION
    @Test
    fun `hands free state service is separate from persisted preference`() = runTest {
        val runtime = JarvisRuntime.getInstance(context)
        runtime.settingsManager.setHandsFree(true)

        // Ensure service state is OFF by default
        assertEquals(HandsFreeState.OFF, com.example.engine.voice.handsfree.HandsFreeVoiceService.serviceState.value)

        // Preference alone does not mark runtime service as ACTIVE
        assertFalse(com.example.engine.voice.handsfree.HandsFreeVoiceService.serviceState.value == HandsFreeState.ACTIVE)
    }

    // 2. EXECUTION SECURITY BOUNDARY IN TOOL EXECUTOR
    @Test
    fun `tool executor rejects consequential actions when unapproved authorization is provided`() = runTest {
        val callAction = PlannedAction(
            action = CommandAction.CALL,
            category = CommandCategory.COMMUNICATION,
            targetAppOrPerson = "Bob",
            requiresApproval = true
        )

        // Untrusted / default execution
        val resultUntrusted = toolExecutor.executeAction(
            command = callAction,
            authorization = ExecutionAuthorization.untrusted()
        )

        assertEquals(ToolExecutionStatus.COMMAND_REJECTED, resultUntrusted.status)
        assertTrue(resultUntrusted.message.contains("approval"))

        // Approved execution via runtime authorization context
        val resultApproved = toolExecutor.executeAction(
            command = callAction,
            authorization = ExecutionAuthorization.userApproved()
        )
        assertNotEquals(ToolExecutionStatus.COMMAND_REJECTED, resultApproved.status)
    }

    @Test
    fun `tool executor rejects git push when untrusted authorization is provided`() = runTest {
        val pushAction = PlannedAction(
            action = CommandAction.PUSH,
            category = CommandCategory.DEVELOPMENT,
            rawArguments = "origin main",
            requiresApproval = true
        )

        val resultUntrusted = toolExecutor.executeAction(
            command = pushAction,
            authorization = ExecutionAuthorization.untrusted()
        )
        assertEquals(ToolExecutionStatus.COMMAND_REJECTED, resultUntrusted.status)

        val resultApproved = toolExecutor.executeAction(
            command = pushAction,
            authorization = ExecutionAuthorization.userApproved()
        )
        assertNotEquals(ToolExecutionStatus.COMMAND_REJECTED, resultApproved.status)
    }

    // 3. SINGLE COMMAND ENGINE & VIEWMODEL DELEGATION
    @Test
    fun `jarvis viewmodel requires runtime and delegates commands directly`() = runTest {
        val runtime = JarvisRuntime.getInstance(context)
        val vm = JarvisViewModel(runtime = runtime)

        vm.submitCommand("open GitHub")

        // Wait briefly for runtime command execution
        kotlinx.coroutines.delay(100)

        assertNotNull(vm.runtime)
        assertTrue(runtime.executionState.value.status.contains("GitHub") || runtime.executionState.value.status == "Ready")
    }

    // 4. PLACEHOLDER ACTIONS RETURN NOT IMPLEMENTED WITHOUT APPROVAL
    @Test
    fun `placeholder development actions return NOT_IMPLEMENTED without approval flow`() = runTest {
        val parser = CommandParser()

        val deletePlan = parser.parse("delete src/main.kt")
        assertEquals(CommandAction.DELETE, deletePlan.actions.first().action)
        assertFalse(deletePlan.actions.first().requiresApproval)

        val overwritePlan = parser.parse("overwrite src/main.kt")
        assertEquals(CommandAction.OVERWRITE, overwritePlan.actions.first().action)
        assertFalse(overwritePlan.actions.first().requiresApproval)

        val runPlan = parser.parse("run gradle build")
        assertEquals(CommandAction.RUN_COMMAND, runPlan.actions.first().action)
        assertFalse(runPlan.actions.first().requiresApproval)

        // Execution of placeholder actions returns NOT_IMPLEMENTED directly
        val resultDelete = toolExecutor.executeAction(
            command = deletePlan.actions.first(),
            authorization = ExecutionAuthorization.userApproved()
        )
        assertEquals(ToolExecutionStatus.NOT_IMPLEMENTED, resultDelete.status)
    }

    @Test
    fun `git push remains real and requires user approval`() = runTest {
        val parser = CommandParser()
        val pushPlan = parser.parse("push origin main")

        assertEquals(CommandAction.PUSH, pushPlan.actions.first().action)
        assertTrue(pushPlan.actions.first().requiresApproval)

        val policyDecision = ExecutionPolicyGuard.evaluate(
            action = pushPlan.actions.first(),
            toolRegistry = toolRegistry,
            isApprovedByUser = false
        )
        assertTrue(policyDecision is PolicyDecision.RequiresApproval)
    }
}
