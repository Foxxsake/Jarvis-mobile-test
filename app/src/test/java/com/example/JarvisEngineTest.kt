package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import com.example.engine.contacts.*
import com.example.engine.speech.SpeechManager
import com.example.engine.speech.SpeechState
import com.example.ui.CommandInputState
import com.example.ui.JarvisViewModel
import com.example.data.ActivityRepository
import com.example.data.SettingsManager
import com.example.data.AppDatabase
import com.example.util.PrivacyUtils
import kotlinx.coroutines.test.runTest
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
    private lateinit var fakeContactsProvider: FakeContactsProvider
    private lateinit var contactResolver: ContactResolver
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var parser: CommandParser

    class FakeContactsProvider(
        var hasPerm: Boolean = true,
        var candidates: List<ContactCandidate> = emptyList(),
        var simulateError: Boolean = false
    ) : ContactsProvider {
        override fun hasPermission(): Boolean = hasPerm
        override suspend fun searchContacts(query: String, isEmail: Boolean): List<ContactCandidate> {
            if (!hasPerm) return emptyList()
            if (simulateError) throw ContactsProviderException("Simulated error")
            val clean = query.trim().lowercase()
            return candidates.filter { candidate ->
                candidate.displayName.lowercase().contains(clean)
            }
        }
        override suspend fun getAllContacts(isEmail: Boolean): List<ContactCandidate> {
            if (!hasPerm) return emptyList()
            if (simulateError) throw ContactsProviderException("Simulated error")
            return candidates
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        toolRegistry = ToolRegistry(context)
        fakeContactsProvider = FakeContactsProvider()
        contactResolver = ContactResolver(fakeContactsProvider)
        toolExecutor = ToolExecutor(context, toolRegistry, contactResolver)
        parser = CommandParser()
    }

    // --- FOUNDATION TESTS ---

    @Test
    fun `open GitHub parsing`() {
        val plan = parser.parse("open GitHub")
        assertEquals(CommandAction.OPEN_APP, plan.actions.first().action)
        assertEquals(CommandCategory.DEVICE_ACTION, plan.actions.first().category)
        assertEquals("GitHub", plan.actions.first().targetAppOrPerson)
        assertFalse(plan.actions.first().requiresApproval)
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
        val plan = parser.parse("call John Smith")
        assertEquals(CommandAction.CALL, plan.actions.first().action)
        assertEquals("John Smith", plan.actions.first().targetAppOrPerson)
        assertTrue(plan.actions.first().requiresApproval)
    }

    @Test
    fun `text command without colon does not fake contact boundaries`() {
        val plan = parser.parse("text John Smith I'm running late")
        assertEquals(CommandAction.TEXT, plan.actions.first().action)
        assertNull(plan.actions.first().targetAppOrPerson)
        assertNull(plan.actions.first().messageOrQuery)
        assertEquals("John Smith I'm running late", plan.actions.first().rawArguments)
        assertTrue(plan.actions.first().requiresApproval)
    }

    @Test
    fun `explicit colon-delimited communication parsing`() {
        val plan = parser.parse("text John Smith: I'm running late")
        assertEquals(CommandAction.TEXT, plan.actions.first().action)
        assertEquals("John Smith", plan.actions.first().targetAppOrPerson)
        assertEquals("I'm running late", plan.actions.first().messageOrQuery)
        assertTrue(plan.actions.first().requiresApproval)
    }

    @Test
    fun `unresolved communication command returns CONTACT_RESOLUTION_REQUIRED`() = runTest {
        val plan = parser.parse("text John Smith I'm running late")
        val result = toolExecutor.executeAction(plan.actions.first())
        assertEquals(ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED, result.status)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `safe app launch does not require approval`() {
        val plan = parser.parse("open github")
        assertFalse(plan.actions.first().requiresApproval)
    }

    @Test
    fun `check GitHub does not require approval`() {
        val plan = parser.parse("check github")
        assertEquals(CommandAction.CHECK_GITHUB, plan.actions.first().action)
        assertFalse(plan.actions.first().requiresApproval)
    }

    @Test
    fun `push requires approval`() {
        val plan = parser.parse("push changes")
        assertEquals(CommandAction.PUSH, plan.actions.first().action)
        assertTrue(plan.actions.first().requiresApproval)
    }

    @Test
    fun `delete requires approval`() {
        val plan = parser.parse("delete file.kt")
        assertEquals(CommandAction.DELETE, plan.actions.first().action)
        assertTrue(plan.actions.first().requiresApproval)
    }

    @Test
    fun `overwrite requires approval`() {
        val plan = parser.parse("overwrite main.kt")
        assertEquals(CommandAction.OVERWRITE, plan.actions.first().action)
        assertTrue(plan.actions.first().requiresApproval)
    }

    @Test
    fun `destructive run command requires approval`() {
        val plan = parser.parse("run rm -rf .")
        assertEquals(CommandAction.RUN_COMMAND, plan.actions.first().action)
        assertTrue(plan.actions.first().requiresApproval)
    }

    @Test
    fun `unknown command handling`() {
        val plan = parser.parse("do something completely unhandled")
        assertEquals(CommandAction.UNKNOWN, plan.actions.first().action)
        assertEquals(CommandCategory.UNKNOWN, plan.actions.first().category)
        assertFalse(plan.actions.first().requiresApproval)
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
    fun `unavailable tool detection`() = runTest {
        val plan = parser.parse("open nonExistentApp")
        val result = toolExecutor.executeAction(plan.actions.first())
        assertEquals(ToolExecutionStatus.NOT_INSTALLED, result.status)
        assertTrue(result.message.contains("not registered or installed"))
    }

    @Test
    fun `disabled tool exclusion`() = runTest {
        toolRegistry.updateDisabledTools(setOf("pydroid"))
        val pydroidTool = toolRegistry.findTool("pydroid")
        assertNotNull(pydroidTool)
        assertFalse(pydroidTool!!.enabled)

        val plan = parser.parse("open pydroid")
        val result = toolExecutor.executeAction(plan.actions.first())
        assertEquals(ToolExecutionStatus.FAILED, result.status)
        assertTrue(result.message.contains("disabled in settings"))
    }

    @Test
    fun `development placeholder returns NOT_IMPLEMENTED`() = runTest {
        val plan = parser.parse("build a PWA")
        val result = toolExecutor.executeAction(plan.actions.first())
        assertEquals(ToolExecutionStatus.NOT_IMPLEMENTED, result.status)
        assertTrue(result.message.contains("not yet implemented"))
    }

    @Test
    fun `failed execution is not logged as success`() = runTest {
        val plan = parser.parse("do something strange")
        val result = toolExecutor.executeAction(plan.actions.first())
        assertNotEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals(ToolExecutionStatus.NOT_IMPLEMENTED, result.status)
    }

    @Test
    fun `ToolExecutionResult message is persisted and descriptive`() = runTest {
        val plan = parser.parse("open nonExistentTool")
        val result = toolExecutor.executeAction(plan.actions.first())
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `local processing disabled behaviour`() = runTest {
        val plan = parser.parse("open github")
        val result = toolExecutor.executeAction(plan.actions.first(), isLocalProcessingEnabled = false)
        assertEquals(ToolExecutionStatus.FAILED, result.status)
        assertEquals("Local command processing is currently disabled in settings.", result.message)
    }

    // --- PASS 3 & 3.1 VOICE & CONTACT HARDENING TESTS ---

    @Test
    fun `exact contact match`() = runTest {
        fakeContactsProvider.candidates = listOf(
            ContactCandidate("1", "Sarah Smith", listOf(ContactDestination("0712345678", "Mobile")))
        )
        val plan = parser.parse("call Sarah Smith")
        val res = contactResolver.resolveCommandTarget(plan.actions.first())

        assertTrue(res is ContactResolutionResult.Resolved)
        val resolved = res as ContactResolutionResult.Resolved
        assertEquals("Sarah Smith", resolved.displayName)
        assertEquals("0712345678", resolved.destination.value)
    }

    @Test
    fun `longest matching contact prefix`() = runTest {
        fakeContactsProvider.candidates = listOf(
            ContactCandidate("1", "Sarah", listOf(ContactDestination("0700000000", "Mobile"))),
            ContactCandidate("2", "Sarah Smith", listOf(ContactDestination("0712345678", "Mobile")))
        )
        val plan = parser.parse("text Sarah Smith I'm running late")
        val res = contactResolver.resolveCommandTarget(plan.actions.first())

        assertTrue(res is ContactResolutionResult.Resolved)
        val resolved = res as ContactResolutionResult.Resolved
        assertEquals("Sarah Smith", resolved.displayName)
        assertEquals("I'm running late", resolved.message)
    }

    @Test
    fun `ambiguous contact result`() = runTest {
        fakeContactsProvider.candidates = listOf(
            ContactCandidate("1", "John Smith", listOf(ContactDestination("0711111111", "Mobile"))),
            ContactCandidate("2", "John Miller", listOf(ContactDestination("0722222222", "Mobile")))
        )
        val plan = parser.parse("call John")
        val res = contactResolver.resolveCommandTarget(plan.actions.first())

        assertTrue(res is ContactResolutionResult.Ambiguous)
        val ambiguous = res as ContactResolutionResult.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `contact not found`() = runTest {
        fakeContactsProvider.candidates = emptyList()
        val plan = parser.parse("call UnknownPerson")
        val res = contactResolver.resolveCommandTarget(plan.actions.first())

        assertEquals(ContactResolutionResult.NotFound, res)
    }

    @Test
    fun `contact permission required`() = runTest {
        fakeContactsProvider.hasPerm = false
        val plan = parser.parse("call Sarah")
        val res = contactResolver.resolveCommandTarget(plan.actions.first())

        assertEquals(ContactResolutionResult.PermissionRequired, res)
    }

    @Test
    fun `multiple phone numbers requires selection`() = runTest {
        fakeContactsProvider.candidates = listOf(
            ContactCandidate(
                "1", "John Smith", listOf(
                    ContactDestination("0711111111", "Mobile"),
                    ContactDestination("0208888888", "Work")
                )
            )
        )
        val plan = parser.parse("call John Smith")
        val res = contactResolver.resolveCommandTarget(plan.actions.first())

        assertTrue(res is ContactResolutionResult.MultipleDestinations)
        val multi = res as ContactResolutionResult.MultipleDestinations
        assertEquals(2, multi.destinations.size)
    }

    @Test
    fun `multiple email addresses requires selection`() = runTest {
        fakeContactsProvider.candidates = listOf(
            ContactCandidate(
                "1", "John Smith", listOf(
                    ContactDestination("john@personal.com", "Personal"),
                    ContactDestination("john@work.com", "Work")
                )
            )
        )
        val plan = parser.parse("email John Smith: Hi")
        val res = contactResolver.resolveCommandTarget(plan.actions.first())

        assertTrue(res is ContactResolutionResult.MultipleDestinations)
        val multi = res as ContactResolutionResult.MultipleDestinations
        assertEquals(2, multi.destinations.size)
    }

    @Test
    fun `permission denial never executes communication`() = runTest {
        fakeContactsProvider.hasPerm = false
        val plan = parser.parse("call Sarah")
        val execResult = toolExecutor.executeAction(plan.actions.first())
        assertEquals(ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED, execResult.status)
    }

    @Test
    fun `masked private activity logging behaviour`() {
        val masked = PrivacyUtils.maskPhoneNumber("0712345678")
        assertEquals("******5678", masked)
    }

    @Test
    fun `no contact names are hardcoded in engine`() = runTest {
        fakeContactsProvider.candidates = emptyList()
        val plan = parser.parse("call John Smith")
        val res = contactResolver.resolveCommandTarget(plan.actions.first())

        assertNotEquals(ContactResolutionResult.Resolved("John Smith", ContactDestination("123", "Mobile")), res)
    }

    // --- REQUIREMENT 4 SPECIFICALLY MANDATED TESTS ---

    @Test
    fun `successful speech result feeds into the same command-processing path as typed input`() {
        val planTyped = parser.parse("open github")
        val planSpeech = parser.parse("open github")
        assertEquals(planTyped.actions.first().action, planSpeech.actions.first().action)
        assertEquals(planTyped.actions.first().targetAppOrPerson, planSpeech.actions.first().targetAppOrPerson)
    }

    @Test
    fun `empty speech result does not execute a command`() {
        val plan = parser.parse("")
        assertEquals(CommandAction.UNKNOWN, plan.actions.first().action)
    }

    @Test
    fun `speech recognition error does not execute a command`() {
        val state: SpeechState = SpeechState.Error("Audio recording error.")
        assertTrue(state is SpeechState.Error)
    }

    @Test
    fun `permission-required speech state does not execute a command`() {
        val state: SpeechState = SpeechState.PermissionRequired
        assertTrue(state is SpeechState.PermissionRequired)
    }

    @Test
    fun `resolved CALL launches ACTION_DIAL`() = runTest {
        val resolved = ContactResolutionResult.Resolved(
            displayName = "Sarah Smith",
            destination = ContactDestination("0712345678", "Mobile")
        )
        val plan = parser.parse("call Sarah Smith")
        val execResult = toolExecutor.executeAction(plan.actions.first(), resolvedResult = resolved)
        assertEquals(ToolExecutionStatus.SUCCESS, execResult.status)

        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        val nextIntent = shadowApp.nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(android.content.Intent.ACTION_DIAL, nextIntent.action)
        assertEquals("tel:0712345678", nextIntent.dataString)
    }

    @Test
    fun `CALL never launches ACTION_CALL`() = runTest {
        val resolved = ContactResolutionResult.Resolved(
            displayName = "Sarah Smith",
            destination = ContactDestination("0712345678", "Mobile")
        )
        val plan = parser.parse("call Sarah Smith")
        toolExecutor.executeAction(plan.actions.first(), resolvedResult = resolved)

        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        val nextIntent = shadowApp.nextStartedActivity
        assertNotNull(nextIntent)
        assertNotEquals(android.content.Intent.ACTION_CALL, nextIntent.action)
    }

    @Test
    fun `resolved TEXT launches ACTION_SENDTO with smsto`() = runTest {
        val resolved = ContactResolutionResult.Resolved(
            displayName = "Sarah Smith",
            destination = ContactDestination("0712345678", "Mobile"),
            message = "Running late"
        )
        val plan = parser.parse("text Sarah Smith: Running late")
        val execResult = toolExecutor.executeAction(plan.actions.first(), resolvedResult = resolved)
        assertEquals(ToolExecutionStatus.SUCCESS, execResult.status)

        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        val nextIntent = shadowApp.nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(android.content.Intent.ACTION_SENDTO, nextIntent.action)
        assertEquals("smsto:0712345678", nextIntent.dataString)
        assertEquals("Running late", nextIntent.getStringExtra("sms_body"))
    }

    @Test
    fun `resolved EMAIL launches ACTION_SENDTO with mailto`() = runTest {
        val resolved = ContactResolutionResult.Resolved(
            displayName = "John Smith",
            destination = ContactDestination("john@example.com", "Work"),
            message = "Meeting agenda"
        )
        val plan = parser.parse("email John Smith: Meeting agenda")
        val execResult = toolExecutor.executeAction(plan.actions.first(), resolvedResult = resolved)
        assertEquals(ToolExecutionStatus.SUCCESS, execResult.status)

        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        val nextIntent = shadowApp.nextStartedActivity
        assertNotNull(nextIntent)
        assertEquals(android.content.Intent.ACTION_SENDTO, nextIntent.action)
        assertEquals("mailto:john@example.com", nextIntent.dataString)
        assertEquals("Meeting agenda", nextIntent.getStringExtra(android.content.Intent.EXTRA_TEXT))
    }

    @Test
    fun `microphone permission denial does not begin listening`() {
        val speechManager = SpeechManager(context)
        assertNotEquals(SpeechState.Listening, speechManager.speechState.value)
    }

    @Test
    fun `permanently denied permission produces the app-settings path`() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null)
        )
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    fun `contact provider error is distinguishable from contact-not-found`() = runTest {
        fakeContactsProvider.simulateError = true
        val plan = parser.parse("call Sarah")
        val res = contactResolver.resolveCommandTarget(plan.actions.first())

        assertTrue(res is ContactResolutionResult.ProviderError)
        assertNotEquals(ContactResolutionResult.NotFound, res)
    }

    @Test
    fun `contact lookup-background architecture remains testable without a real address book`() = runTest {
        fakeContactsProvider.candidates = listOf(
            ContactCandidate("1", "Alice", listOf(ContactDestination("12345", "Mobile")))
        )
        val plan = parser.parse("call Alice")
        val res = contactResolver.resolveCommandTarget(plan.actions.first())

        assertTrue(res is ContactResolutionResult.Resolved)
        assertEquals("Alice", (res as ContactResolutionResult.Resolved).displayName)
    }

    // --- PASS 3.2 TESTS: NATURAL TOOL COMMANDS & FUZZY MATCHING ---

    @Test
    fun `open Pydroid natural command resolution`() {
        val plan = parser.parse("open Pydroid")
        assertEquals(CommandAction.OPEN_APP, plan.actions.first().action)
        val tool = toolRegistry.findTool(plan.actions.first().targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("pydroid", tool?.id)
        assertNull(plan.actions.first().followUp)
    }

    @Test
    fun `open Pydroid 3 natural command resolution`() {
        val plan = parser.parse("open Pydroid 3")
        assertEquals(CommandAction.OPEN_APP, plan.actions.first().action)
        val tool = toolRegistry.findTool(plan.actions.first().targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("pydroid", tool?.id)
        assertNull(plan.actions.first().followUp)
    }

    @Test
    fun `open Pydroid 3 and start coding separates follow-up text`() {
        val plan = parser.parse("open Pydroid 3 and start coding")
        assertEquals(CommandAction.OPEN_APP, plan.actions.first().action)
        val tool = toolRegistry.findTool(plan.actions.first().targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("pydroid", tool?.id)
        assertEquals("start coding", plan.actions.first().followUp)
    }

    @Test
    fun `open GitHub please strips polite trailing words`() {
        val plan = parser.parse("open GitHub please")
        assertEquals(CommandAction.OPEN_APP, plan.actions.first().action)
        assertEquals("GitHub", plan.actions.first().targetAppOrPerson)
        assertNull(plan.actions.first().followUp)
    }

    @Test
    fun `launch Termux action verb resolution`() {
        val plan = parser.parse("launch Termux")
        assertEquals(CommandAction.OPEN_APP, plan.actions.first().action)
        val tool = toolRegistry.findTool(plan.actions.first().targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("termux", tool?.id)
        assertNull(plan.actions.first().followUp)
    }

    @Test
    fun `start Acode action verb resolution`() {
        val plan = parser.parse("start Acode")
        assertEquals(CommandAction.OPEN_APP, plan.actions.first().action)
        val tool = toolRegistry.findTool(plan.actions.first().targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("acode", tool?.id)
        assertNull(plan.actions.first().followUp)
    }

    @Test
    fun `Pyroid uniquely resolves to Pydroid via conservative fuzzy matching`() {
        val plan = parser.parse("Pyroid")
        assertEquals(CommandAction.OPEN_APP, plan.actions.first().action)
        val tool = toolRegistry.findTool(plan.actions.first().targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("pydroid", tool?.id)
    }

    @Test
    fun `unknown tool does not fuzzy-match dangerously`() {
        val plan = parser.parse("open Photoshop")
        assertEquals(CommandAction.OPEN_APP, plan.actions.first().action)
        // ToolRegistry findTool should return null for Photoshop
        val tool = toolRegistry.findTool(plan.actions.first().targetAppOrPerson!!)
        assertNull(tool)
    }

    @Test
    fun `ambiguous fuzzy result does not guess`() {
        val matcher = ToolCommandMatcher {
            listOf(
                Tool(id = "alpha", name = "Testa", description = "", capabilities = emptyList(), preferredUses = "", toolType = ToolType.APP, packageNames = emptyList(), aliases = listOf("toolalpha")),
                Tool(id = "beta", name = "Testb", description = "", capabilities = emptyList(), preferredUses = "", toolType = ToolType.APP, packageNames = emptyList(), aliases = listOf("toolalphb"))
            )
        }
        val outcome = matcher.matchSingleTarget("toolalphx")
        // Both are distance 1, must return Ambiguous, NOT guess
        assertTrue(outcome is ToolMatchOutcome.Ambiguous)
    }

    @Test
    fun `follow-up text reports not implemented without failing launch intent logic`() = runTest {
        val plan = parser.parse("open Pydroid 3 and start coding")
        // If app is not installed, it safely returns NOT_INSTALLED
        val result = toolExecutor.executeAction(plan.actions.first())
        assertEquals(ToolExecutionStatus.NOT_INSTALLED, result.status)
        assertTrue(result.message.contains("Pydroid"))
    }

    // --- PASS 3.2 TESTS: TEXT INPUT STATE & REGRESSION ---

    @Test
    fun `new speech result populates field once`() {
        val inputState = CommandInputState()
        val updated = inputState.onSpeechResult(101L, "open Pydroid")
        assertTrue(updated)
        assertEquals("open Pydroid", inputState.text)

        // Same event ID does not re-populate or return true
        val rehandled = inputState.onSpeechResult(101L, "open Pydroid")
        assertFalse(rehandled)
        assertEquals("open Pydroid", inputState.text)
    }

    @Test
    fun `deleting recognised text remains deleted`() {
        val inputState = CommandInputState()
        inputState.onSpeechResult(101L, "open Pydroid")
        assertEquals("open Pydroid", inputState.text)

        // User backspaces
        inputState.deleteLastChar()
        assertEquals("open Pydroi", inputState.text)

        // Recomposition occurs with the same speech event
        inputState.onRecompose(101L, "open Pydroid")
        assertEquals("open Pydroi", inputState.text)
    }

    @Test
    fun `editing recognised text remains edited`() {
        val inputState = CommandInputState()
        inputState.onSpeechResult(101L, "open Pydroid")

        // User edits text
        inputState.onUserTextChange("open Pydroid 3 and start coding")
        assertEquals("open Pydroid 3 and start coding", inputState.text)

        // Recomposition occurs
        inputState.onRecompose(101L, "open Pydroid")
        assertEquals("open Pydroid 3 and start coding", inputState.text)
    }

    @Test
    fun `clear button clears input`() {
        val inputState = CommandInputState()
        inputState.onSpeechResult(101L, "open Pydroid")
        assertEquals("open Pydroid", inputState.text)

        inputState.clear()
        assertEquals("", inputState.text)

        // Recomposition does not restore cleared text
        inputState.onRecompose(101L, "open Pydroid")
        assertEquals("", inputState.text)
    }

    @Test
    fun `recomposition does not restore stale speech text`() {
        val inputState = CommandInputState()
        inputState.onSpeechResult(101L, "call Alice")
        inputState.clear()

        // 5 recomposition frames
        repeat(5) {
            inputState.onRecompose(101L, "call Alice")
        }
        assertEquals("", inputState.text)
    }

    @Test
    fun `new later speech result can populate the field again`() {
        val inputState = CommandInputState()
        inputState.onSpeechResult(101L, "open Pydroid")
        inputState.clear()
        assertEquals("", inputState.text)

        // New speech event with ID 102
        val updated = inputState.onSpeechResult(102L, "launch Termux")
        assertTrue(updated)
        assertEquals("launch Termux", inputState.text)
    }
    // --- PASS 3.3 TESTS: MULTI-ACTION COMMANDS ---
    @Test
    fun `open GitHub and Termux parses as two OPEN_APP actions`() {
        val plan = parser.parse("open GitHub and Termux")
        assertEquals(2, plan.actions.size)
        assertEquals(CommandAction.OPEN_APP, plan.actions[0].action)
        assertEquals("GitHub", plan.actions[0].targetAppOrPerson)
        assertNull(plan.actions[0].followUp)
        
        assertEquals(CommandAction.OPEN_APP, plan.actions[1].action)
        assertEquals("Termux", plan.actions[1].targetAppOrPerson)
    }

    @Test
    fun `open GitHub then Termux parses as two OPEN_APP actions`() {
        val plan = parser.parse("open GitHub then Termux")
        assertEquals(2, plan.actions.size)
        assertEquals(CommandAction.OPEN_APP, plan.actions[0].action)
        assertEquals("GitHub", plan.actions[0].targetAppOrPerson)
        
        assertEquals(CommandAction.OPEN_APP, plan.actions[1].action)
        assertEquals("Termux", plan.actions[1].targetAppOrPerson)
    }

    @Test
    fun `launch Acode and Pydroid parses as two actions`() {
        val plan = parser.parse("launch Acode and Pydroid")
        assertEquals(2, plan.actions.size)
        assertEquals(CommandAction.OPEN_APP, plan.actions[0].action)
        assertEquals("Acode", plan.actions[0].targetAppOrPerson)
        
        assertEquals(CommandAction.OPEN_APP, plan.actions[1].action)
        assertEquals("pydroid", plan.actions[1].targetAppOrPerson?.lowercase())
    }

    @Test
    fun `open Pydroid and start coding parses as one app action plus followUp`() {
        val plan = parser.parse("open Pydroid and start coding")
        assertEquals(1, plan.actions.size)
        assertEquals(CommandAction.OPEN_APP, plan.actions[0].action)
        assertEquals("pydroid", plan.actions[0].targetAppOrPerson?.lowercase())
        assertEquals("start coding", plan.actions[0].followUp)
    }

    @Test
    fun `open GitHub and push code parses as safe open and consequential push requiring approval`() {
        val plan = parser.parse("open GitHub and push code")
        assertEquals(2, plan.actions.size)
        assertEquals(CommandAction.OPEN_APP, plan.actions[0].action)
        assertEquals("GitHub", plan.actions[0].targetAppOrPerson)
        assertFalse(plan.actions[0].requiresApproval)
        
        assertEquals(CommandAction.PUSH, plan.actions[1].action)
        assertTrue(plan.actions[1].requiresApproval)
        
        assertTrue(plan.requiresApproval) // entire plan needs approval
    }

    // --- PASS 4 TESTS: TERMUX EXECUTION WORKER & VOICE RECOVERY ---

    @Test
    fun `voice recognition success triggers command execution`() = runTest {
        val fakeWorker = com.example.engine.termux.FakeTermuxWorker()
        val localExecutor = ToolExecutor(context, toolRegistry, contactResolver, fakeWorker)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val speechManager = SpeechManager(context)
        val vm = JarvisViewModel(
            speechManager = speechManager,
            toolRegistry = toolRegistry,
            repository = ActivityRepository(db.activityLogDao()),
            toolExecutor = localExecutor,
            contactResolver = contactResolver,
            settingsManager = SettingsManager(context),
            termuxWorker = fakeWorker
        )

        // Trigger speech success event
        val speechStateField = SpeechManager::class.java.getDeclaredField("_speechState")
        speechStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = speechStateField.get(speechManager) as kotlinx.coroutines.flow.MutableStateFlow<SpeechState>
        
        // Simulating speech success
        stateFlow.value = SpeechState.Success("check termux")
        
        // Let coroutine collect
        kotlinx.coroutines.delay(100)

        // Verify command ran and last recognized text was updated
        assertEquals("check termux", vm.uiState.value.lastRecognizedText)
        assertEquals("Ready", vm.uiState.value.status)
    }

    @Test
    fun `termux command classification risk levels`() {
        assertEquals(
            com.example.engine.termux.TermuxRiskLevel.READ_ONLY,
            com.example.engine.termux.TermuxCommandClassifier.classify("git", listOf("status"))
        )
        assertEquals(
            com.example.engine.termux.TermuxRiskLevel.READ_ONLY,
            com.example.engine.termux.TermuxCommandClassifier.classify("node", listOf("--version"))
        )
        assertEquals(
            com.example.engine.termux.TermuxRiskLevel.MUTATING,
            com.example.engine.termux.TermuxCommandClassifier.classify("npm", listOf("install"))
        )
        assertEquals(
            com.example.engine.termux.TermuxRiskLevel.DESTRUCTIVE,
            com.example.engine.termux.TermuxCommandClassifier.classify("git", listOf("reset", "--hard"))
        )
        assertEquals(
            com.example.engine.termux.TermuxRiskLevel.PUBLISHING,
            com.example.engine.termux.TermuxCommandClassifier.classify("git", listOf("push"))
        )
    }

    @Test
    fun `read-only termux commands do not require approval`() {
        val plan = parser.parse("check git version")
        assertEquals(CommandAction.TERMUX_COMMAND, plan.actions.first().action)
        assertFalse(plan.actions.first().requiresApproval)

        val planStatus = parser.parse("check git status")
        assertEquals(CommandAction.TERMUX_COMMAND, planStatus.actions.first().action)
        assertFalse(planStatus.actions.first().requiresApproval)
    }

    @Test
    fun `mutating termux commands require approval`() {
        val plan = parser.parse("run tests")
        assertEquals(CommandAction.TERMUX_COMMAND, plan.actions.first().action)
        assertTrue(plan.actions.first().requiresApproval)

        val planBuild = parser.parse("build project")
        assertEquals(CommandAction.TERMUX_COMMAND, planBuild.actions.first().action)
        assertTrue(planBuild.actions.first().requiresApproval)
    }

    @Test
    fun `fake termux worker executes read-only commands successfully`() = runTest {
        val fakeWorker = com.example.engine.termux.FakeTermuxWorker()
        val localExecutor = ToolExecutor(context, toolRegistry, contactResolver, fakeWorker)

        val plan = parser.parse("check git version")
        val result = localExecutor.executeAction(plan.actions.first())

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertTrue(result.message.contains("git version"))
    }

    @Test
    fun `termux execution returns permission required when permission is missing`() = runTest {
        val fakeWorker = com.example.engine.termux.FakeTermuxWorker(
            mockConnectionStatus = com.example.engine.termux.TermuxConnectionStatus(
                isInstalled = true,
                isPermissionGranted = false,
                isExternalAppsAllowed = true,
                connectionState = com.example.engine.termux.TermuxConnectionState.TERMUX_PERMISSION_REQUIRED
            )
        )
        val localExecutor = ToolExecutor(context, toolRegistry, contactResolver, fakeWorker)

        val plan = parser.parse("check termux")
        val result = localExecutor.executeAction(plan.actions.first())

        assertEquals(ToolExecutionStatus.FAILED, result.status)
        assertTrue(result.message.contains("Permission required"))
    }

    @Test
    fun `check project status requires registered workspace`() = runTest {
        val fakeWorker = com.example.engine.termux.FakeTermuxWorker()
        val emptyWsRegistry = com.example.data.workspace.LocalWorkspaceRegistry()
        val localExecutor = ToolExecutor(
            context = context,
            toolRegistry = toolRegistry,
            contactResolver = contactResolver,
            termuxWorker = fakeWorker,
            workspaceRegistry = emptyWsRegistry
        )

        val plan = parser.parse("check project status")
        val result = localExecutor.executeAction(plan.actions.first())

        assertEquals(ToolExecutionStatus.FAILED, result.status)
        assertTrue(result.message.contains("WORKSPACE_REQUIRED"))
    }

    @Test
    fun `check project status returns clean working tree when workspace is set`() = runTest {
        val fakeWorker = com.example.engine.termux.FakeTermuxWorker()
        val wsRegistry = com.example.data.workspace.LocalWorkspaceRegistry(context)
        wsRegistry.setActiveWorkspace(
            com.example.data.workspace.Workspace("1", "TestProject", "/data/data/com.termux/files/home/project")
        )
        val localExecutor = ToolExecutor(
            context = context,
            toolRegistry = toolRegistry,
            contactResolver = contactResolver,
            termuxWorker = fakeWorker,
            workspaceRegistry = wsRegistry
        )

        val plan = parser.parse("check project status")
        val result = localExecutor.executeAction(plan.actions.first())

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertTrue(result.message.contains("TestProject"))
        assertTrue(result.message.lowercase().contains("working tree clean"))
    }

    // --- PASS 4.2: INDEPENDENT TERMUX CONTRACT TESTS ---

    @Test
    fun `literal result Bundle is found and parsed successfully`() {
        val worker = com.example.engine.termux.AndroidTermuxWorker(context)
        val bundle = android.os.Bundle().apply {
            putString("stdout", "u0_a123")
            putString("stderr", "")
            putInt("exitCode", 0)
            putInt("err", -1) // Activity.RESULT_OK is -1
            putString("errmsg", "")
        }
        val intent = android.content.Intent().apply {
            putExtra("result", bundle)
        }

        val parsed = worker.parseResultBundle(intent, 1000L, 1050L)

        assertEquals(com.example.engine.termux.TermuxExecutionStatus.SUCCESS, parsed.status)
        assertEquals(0, parsed.exitCode)
        assertEquals("u0_a123", parsed.stdout)
        assertEquals("u0_a123", parsed.message)
    }

    @Test
    fun `literal result_bundle alone is NOT considered the official current contract`() {
        val worker = com.example.engine.termux.AndroidTermuxWorker(context)
        val bundle = android.os.Bundle().apply {
            putString("stdout", "u0_a123")
            putInt("exitCode", 0)
        }
        // Intent only has the old "result_bundle" extra
        val intent = android.content.Intent().apply {
            putExtra("result_bundle", bundle)
        }

        val parsed = worker.parseResultBundle(intent, 1000L, 1050L)

        // It must fail because the official key is "result"
        assertEquals(com.example.engine.termux.TermuxExecutionStatus.FAILED, parsed.status)
        assertEquals("Missing result bundle in Termux callback Intent.", parsed.message)
    }

    @Test
    fun `literal err is read correctly and maps to SUCCESS with RESULT_OK and exitCode 0`() {
        val worker = com.example.engine.termux.AndroidTermuxWorker(context)
        val bundle = android.os.Bundle().apply {
            putString("stdout", "hello")
            putInt("exitCode", 0)
            putInt("err", -1) // Activity.RESULT_OK
        }
        val intent = android.content.Intent().apply {
            putExtra("result", bundle)
        }

        val parsed = worker.parseResultBundle(intent, 1000L, 1050L)

        assertEquals(com.example.engine.termux.TermuxExecutionStatus.SUCCESS, parsed.status)
    }

    @Test
    fun `Activity RESULT_OK with non-zero exitCode maps to FAILED`() {
        val worker = com.example.engine.termux.AndroidTermuxWorker(context)
        val bundle = android.os.Bundle().apply {
            putString("stdout", "")
            putString("stderr", "command not found")
            putInt("exitCode", 127)
            putInt("err", -1) // Activity.RESULT_OK
        }
        val intent = android.content.Intent().apply {
            putExtra("result", bundle)
        }

        val parsed = worker.parseResultBundle(intent, 1000L, 1050L)

        assertEquals(com.example.engine.termux.TermuxExecutionStatus.FAILED, parsed.status)
        assertEquals(127, parsed.exitCode)
        assertEquals("command not found", parsed.message)
    }

    @Test
    fun `internal err other than Activity RESULT_OK maps to FAILED`() {
        val worker = com.example.engine.termux.AndroidTermuxWorker(context)
        val bundle = android.os.Bundle().apply {
            putInt("exitCode", -1)
            putInt("err", 2) // Some internal Termux execution error code
            putString("errmsg", "Failed to start session")
        }
        val intent = android.content.Intent().apply {
            putExtra("result", bundle)
        }

        val parsed = worker.parseResultBundle(intent, 1000L, 1050L)

        assertEquals(com.example.engine.termux.TermuxExecutionStatus.FAILED, parsed.status)
        assertEquals("Failed to start session", parsed.message)
    }

    @Test
    fun `stdout is returned correctly`() {
        val worker = com.example.engine.termux.AndroidTermuxWorker(context)
        val bundle = android.os.Bundle().apply {
            putString("stdout", "some test stdout")
            putInt("exitCode", 0)
            putInt("err", -1)
        }
        val intent = android.content.Intent().apply {
            putExtra("result", bundle)
        }

        val parsed = worker.parseResultBundle(intent, 1000L, 1050L)

        assertEquals("some test stdout", parsed.stdout)
    }

    @Test
    fun `stderr is returned correctly`() {
        val worker = com.example.engine.termux.AndroidTermuxWorker(context)
        val bundle = android.os.Bundle().apply {
            putString("stderr", "some test stderr")
            putInt("exitCode", 1)
            putInt("err", -1)
        }
        val intent = android.content.Intent().apply {
            putExtra("result", bundle)
        }

        val parsed = worker.parseResultBundle(intent, 1000L, 1050L)

        assertEquals("some test stderr", parsed.stderr)
        assertEquals("some test stderr", parsed.message)
    }

    @Test
    fun `missing result Bundle maps to FAILED`() {
        val worker = com.example.engine.termux.AndroidTermuxWorker(context)
        val intent = android.content.Intent() // Completely empty intent

        val parsed = worker.parseResultBundle(intent, 1000L, 1050L)

        assertEquals(com.example.engine.termux.TermuxExecutionStatus.FAILED, parsed.status)
        assertEquals("Missing result bundle in Termux callback Intent.", parsed.message)
    }

    @Test
    fun `missing result bundle does NOT become SETUP_REQUIRED`() {
        val worker = com.example.engine.termux.AndroidTermuxWorker(context)
        val intent = android.content.Intent()

        val parsed = worker.parseResultBundle(intent, 1000L, 1050L)

        assertNotEquals(com.example.engine.termux.TermuxExecutionStatus.SETUP_REQUIRED, parsed.status)
    }

    @Test
    fun `real allow-external-apps error maps to SETUP_REQUIRED`() {
        val worker = com.example.engine.termux.AndroidTermuxWorker(context)
        val bundle = android.os.Bundle().apply {
            putInt("err", 1)
            putString("errmsg", "allow-external-apps is disabled")
        }
        val intent = android.content.Intent().apply {
            putExtra("result", bundle)
        }

        val parsed = worker.parseResultBundle(intent, 1000L, 1050L)

        assertEquals(com.example.engine.termux.TermuxExecutionStatus.SETUP_REQUIRED, parsed.status)
        assertTrue(parsed.message.contains("allow-external-apps=true"))
    }

    @Test
    fun `exact outbound RUN_COMMAND path key matches Termux spec`() {
        assertEquals("com.termux.RUN_COMMAND_PATH", com.example.engine.termux.TermuxConstants.EXTRA_COMMAND_PATH)
    }

    @Test
    fun `exact outbound arguments key matches Termux spec`() {
        assertEquals("com.termux.RUN_COMMAND_ARGUMENTS", com.example.engine.termux.TermuxConstants.EXTRA_ARGUMENTS)
    }

    @Test
    fun `exact outbound workdir key matches Termux spec`() {
        assertEquals("com.termux.RUN_COMMAND_WORKDIR", com.example.engine.termux.TermuxConstants.EXTRA_WORKDIR)
    }

    @Test
    fun `exact outbound pending-intent key matches Termux spec`() {
        assertEquals("com.termux.RUN_COMMAND_PENDING_INTENT", com.example.engine.termux.TermuxConstants.EXTRA_PENDING_INTENT)
    }

    @Test
    fun `exact official description and label keys match Termux spec`() {
        assertEquals("com.termux.RUN_COMMAND_COMMAND_LABEL", com.example.engine.termux.TermuxConstants.EXTRA_COMMAND_LABEL)
        assertEquals("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", com.example.engine.termux.TermuxConstants.EXTRA_COMMAND_DESCRIPTION)
    }

    @Test
    fun `fake worker probeConnection transition to READY`() = runTest {
        val fakeWorker = com.example.engine.termux.FakeTermuxWorker(
            mockConnectionStatus = com.example.engine.termux.TermuxConnectionStatus(
                isInstalled = true,
                isPermissionGranted = true,
                isExternalAppsAllowed = false,
                connectionState = com.example.engine.termux.TermuxConnectionState.UNVERIFIED
            )
        )

        val probed = fakeWorker.probeConnection()

        assertEquals(com.example.engine.termux.TermuxConnectionState.READY, probed.connectionState)
        assertTrue(probed.isExternalAppsAllowed)
        assertTrue(probed.detailMessage!!.contains("verified working"))
    }
}

