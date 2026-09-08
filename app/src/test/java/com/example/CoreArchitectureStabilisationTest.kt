package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AccessPolicy
import com.example.data.CapabilityPermission
import com.example.data.CapabilityType
import com.example.engine.CommandAction
import com.example.engine.CommandCategory
import com.example.engine.JarvisRuntime
import com.example.engine.PlannedAction
import com.example.engine.ToolRegistry
import com.example.engine.audio.AudioSessionManager
import com.example.engine.audio.AudioSessionOwner
import com.example.engine.policy.ExecutionPolicyGuard
import com.example.engine.policy.PolicyDecision
import com.example.engine.termux.AndroidTermuxWorker
import com.example.engine.termux.TermuxCommandClassifier
import com.example.engine.termux.TermuxCommandRequest
import com.example.engine.termux.TermuxExecutionStatus
import com.example.engine.termux.TermuxRiskLevel
import com.example.engine.termux.severity
import com.example.util.PrivacyUtils
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreArchitectureStabilisationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        JarvisRuntime.setInstance(null)
    }

    // ==========================================
    // 1. Audio Session Manager Tests
    // ==========================================

    @Test
    fun `audio session manager grants exclusive session and manages transitions`() {
        val manager = AudioSessionManager()
        assertEquals(AudioSessionOwner.NONE, manager.currentOwner.value)

        // Wake word requests session
        assertTrue(manager.requestSession(AudioSessionOwner.WAKE_WORD))
        assertEquals(AudioSessionOwner.WAKE_WORD, manager.currentOwner.value)

        // STT requests session while WAKE_WORD active -> granted handoff
        assertTrue(manager.requestSession(AudioSessionOwner.SPEECH_TO_TEXT))
        assertEquals(AudioSessionOwner.SPEECH_TO_TEXT, manager.currentOwner.value)

        // Wake word requests session while STT active -> rejected
        assertFalse(manager.requestSession(AudioSessionOwner.WAKE_WORD))
        assertEquals(AudioSessionOwner.SPEECH_TO_TEXT, manager.currentOwner.value)

        // Releasing from a non-owner does nothing
        manager.releaseSession(AudioSessionOwner.WAKE_WORD)
        assertEquals(AudioSessionOwner.SPEECH_TO_TEXT, manager.currentOwner.value)

        // Releasing from true owner resets to NONE
        manager.releaseSession(AudioSessionOwner.SPEECH_TO_TEXT)
        assertEquals(AudioSessionOwner.NONE, manager.currentOwner.value)
    }

    // ==========================================
    // 2. Execution Policy Guard Tests
    // ==========================================

    @Test
    fun `policy guard permanently blocks automatic money spending`() {
        val fakePolicyDao = FakeAppPolicyDao()
        val toolRegistry = ToolRegistry(context, fakePolicyDao)

        val payAction = PlannedAction(
            action = CommandAction.RUN_COMMAND,
            category = CommandCategory.DEVICE_ACTION,
            rawArguments = "pay $50 to Bob",
            requiresApproval = false
        )

        val decision = ExecutionPolicyGuard.evaluate(payAction, toolRegistry, isApprovedByUser = false)
        assertTrue("Financial action must be permanently blocked", decision is PolicyDecision.Blocked)
        assertEquals("Automatic financial transactions and money spending are permanently prohibited by safety policy.", (decision as PolicyDecision.Blocked).reason)
    }

    @Test
    fun `policy guard enforces user approval for communication and destructive actions`() {
        val fakePolicyDao = FakeAppPolicyDao()
        val toolRegistry = ToolRegistry(context, fakePolicyDao)

        val callAction = PlannedAction(
            action = CommandAction.CALL,
            category = CommandCategory.COMMUNICATION,
            targetAppOrPerson = "Alice",
            requiresApproval = true
        )

        // Without user approval -> RequiresApproval
        val decisionUnapproved = ExecutionPolicyGuard.evaluate(callAction, toolRegistry, isApprovedByUser = false)
        assertTrue(decisionUnapproved is PolicyDecision.RequiresApproval)

        // With user approval -> Allowed
        val decisionApproved = ExecutionPolicyGuard.evaluate(callAction, toolRegistry, isApprovedByUser = true)
        assertTrue(decisionApproved is PolicyDecision.Allowed)
    }

    @Test
    fun `policy guard enforces tool enabled policy`() = runTest {
        val fakePolicyDao = FakeAppPolicyDao()
        val toolRegistry = ToolRegistry(context, fakePolicyDao)
        toolRegistry.updateDisabledTools(setOf("termux"))

        var attempt = 0
        while (toolRegistry.tools.value.find { it.id == "termux" }?.enabled == true && attempt < 50) {
            kotlinx.coroutines.delay(10)
            attempt++
        }

        val termuxAction = PlannedAction(
            action = CommandAction.TERMUX_COMMAND,
            category = CommandCategory.DEVELOPMENT,
            rawArguments = "ls",
            requiresApproval = false
        )

        val decision = ExecutionPolicyGuard.evaluate(termuxAction, toolRegistry, isApprovedByUser = true)
        assertTrue(decision is PolicyDecision.Blocked)
        assertTrue((decision as PolicyDecision.Blocked).reason.contains("disabled"))
    }

    // ==========================================
    // 3. Termux Command Classifier Tests
    // ==========================================

    @Test
    fun `termux classifier identifies destructive find variants`() {
        val deleteFind = TermuxCommandClassifier.classifyCommandLine("find . -name '*.tmp' -delete")
        assertEquals(TermuxRiskLevel.DESTRUCTIVE, deleteFind)

        val execRmFind = TermuxCommandClassifier.classifyCommandLine("find /sdcard -name '*.log' -exec rm -rf {} +")
        assertEquals(TermuxRiskLevel.DESTRUCTIVE, execRmFind)

        val safeFind = TermuxCommandClassifier.classifyCommandLine("find . -name '*.kt' -print")
        assertEquals(TermuxRiskLevel.READ_ONLY, safeFind)
    }

    @Test
    fun `termux classifier risk severity levels are strictly monotonic`() {
        assertTrue(TermuxRiskLevel.READ_ONLY.severity < TermuxRiskLevel.MUTATING.severity)
        assertTrue(TermuxRiskLevel.MUTATING.severity < TermuxRiskLevel.PUBLISHING.severity)
        assertTrue(TermuxRiskLevel.PUBLISHING.severity < TermuxRiskLevel.DESTRUCTIVE.severity)
    }

    // ==========================================
    // 4. Android Termux Worker Defence In Depth
    // ==========================================

    @Test
    fun `termux worker rejects understated risk levels`() = runTest {
        val worker = AndroidTermuxWorker(context)
        // Send a destructive command with an understated READ_ONLY declaration
        val sneakyRequest = TermuxCommandRequest(
            executablePath = "/data/data/com.termux/files/usr/bin/rm",
            arguments = listOf("-rf", "/"),
            description = "Sneaky deletion",
            riskLevel = TermuxRiskLevel.READ_ONLY // Understated!
        )

        val result = worker.executeCommand(sneakyRequest)
        assertEquals(TermuxExecutionStatus.COMMAND_REJECTED, result.status)
        assertTrue(result.message.lowercase().contains("understated"))
    }

    // ==========================================
    // 5. Privacy Utils Redaction Tests
    // ==========================================

    @Test
    fun `privacy utils masks phone numbers, emails, tokens and payloads`() {
        val maskedPhone = PrivacyUtils.maskPhoneNumber("+447123456789")
        assertEquals("********6789", maskedPhone)

        val maskedEmail = PrivacyUtils.maskEmail("developer@example.com")
        assertEquals("d*******r@example.com", maskedEmail)

        val queryWithToken = "git clone token: ghp_1234567890abcdefghijklmnopqrstuvwxyz"
        val sanitizedQuery = PrivacyUtils.redactSensitiveText(queryWithToken)
        assertFalse(sanitizedQuery.contains("ghp_1234567890abcdefghijklmnopqrstuvwxyz"))
        assertTrue(sanitizedQuery.contains("[REDACTED]"))

        val textWithApiKey = "api_key: AIzaSyA1234567890abcdefghij"
        val sanitizedApiKey = PrivacyUtils.redactSensitiveText(textWithApiKey)
        assertFalse(sanitizedApiKey.contains("AIzaSyA1234567890abcdefghij"))
        assertTrue(sanitizedApiKey.contains("[REDACTED]"))
    }

    // ==========================================
    // 6. Capability Permissions Model
    // ==========================================

    @Test
    fun `capability permissions model correctly reflects access levels`() {
        val micCap = CapabilityPermission(
            targetId = "termux",
            capability = CapabilityType.TERMUX_MUTATE_FILES,
            policy = AccessPolicy.ASK_EACH_TIME
        )
        assertEquals(AccessPolicy.ASK_EACH_TIME, micCap.policy)
        assertEquals(CapabilityType.TERMUX_MUTATE_FILES, micCap.capability)

        val blockedCap = micCap.copy(policy = AccessPolicy.BLOCK)
        assertEquals(AccessPolicy.BLOCK, blockedCap.policy)
    }

    // ==========================================
    // 7. JarvisRuntime Concurrency & Pending Safety
    // ==========================================

    @Test
    fun `jarvis runtime does not overwrite active pending approval`() = runTest {
        val runtime = JarvisRuntime.getInstance(context)

        // Trigger a command requiring approval
        runtime.executeCommand("push code")

        // Wait for async execution on runtimeScope and idle main looper
        var attempts = 0
        while (runtime.executionState.value.pendingApproval == null && attempts < 100) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            attempts++
        }

        val firstState = runtime.executionState.value
        assertNotNull("First command should require approval", firstState.pendingApproval)

        // Attempt to execute another command while first is waiting
        runtime.executeCommand("open GitHub")
        
        attempts = 0
        while (!runtime.executionState.value.status.contains("pending approval") && attempts < 100) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            attempts++
        }

        val secondState = runtime.executionState.value
        // Pending approval must NOT be replaced/wiped
        assertNotNull("Pending approval must be preserved", secondState.pendingApproval)
        assertEquals(firstState.pendingApproval?.originalText, secondState.pendingApproval?.originalText)

        // Reject pending
        runtime.rejectPending()
        attempts = 0
        while (runtime.executionState.value.pendingApproval != null && attempts < 100) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            attempts++
        }
        assertNull(runtime.executionState.value.pendingApproval)
    }
}
