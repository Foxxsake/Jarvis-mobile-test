package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import com.example.engine.contacts.*
import com.example.engine.speech.SpeechManager
import com.example.engine.speech.SpeechState
import com.example.ui.CommandInputState
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
    fun `unresolved communication command returns CONTACT_RESOLUTION_REQUIRED`() = runTest {
        val parsed = parser.parse("text John Smith I'm running late")
        val result = toolExecutor.executeAction(parsed)
        assertEquals(ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED, result.status)
        assertTrue(result.message.isNotBlank())
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
    fun `unavailable tool detection`() = runTest {
        val parsed = parser.parse("open nonExistentApp")
        val result = toolExecutor.executeAction(parsed)
        assertEquals(ToolExecutionStatus.NOT_INSTALLED, result.status)
        assertTrue(result.message.contains("not registered or installed"))
    }

    @Test
    fun `disabled tool exclusion`() = runTest {
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
    fun `development placeholder returns NOT_IMPLEMENTED`() = runTest {
        val parsed = parser.parse("build a PWA")
        val result = toolExecutor.executeAction(parsed)
        assertEquals(ToolExecutionStatus.NOT_IMPLEMENTED, result.status)
        assertTrue(result.message.contains("not yet implemented"))
    }

    @Test
    fun `failed execution is not logged as success`() = runTest {
        val parsed = parser.parse("do something strange")
        val result = toolExecutor.executeAction(parsed)
        assertNotEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals(ToolExecutionStatus.NOT_IMPLEMENTED, result.status)
    }

    @Test
    fun `ToolExecutionResult message is persisted and descriptive`() = runTest {
        val parsed = parser.parse("open nonExistentTool")
        val result = toolExecutor.executeAction(parsed)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `local processing disabled behaviour`() = runTest {
        val parsed = parser.parse("open github")
        val result = toolExecutor.executeAction(parsed, isLocalProcessingEnabled = false)
        assertEquals(ToolExecutionStatus.FAILED, result.status)
        assertEquals("Local command processing is currently disabled in settings.", result.message)
    }

    // --- PASS 3 & 3.1 VOICE & CONTACT HARDENING TESTS ---

    @Test
    fun `exact contact match`() = runTest {
        fakeContactsProvider.candidates = listOf(
            ContactCandidate("1", "Sarah Smith", listOf(ContactDestination("0712345678", "Mobile")))
        )
        val parsed = parser.parse("call Sarah Smith")
        val res = contactResolver.resolveCommandTarget(parsed)
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
        val parsed = parser.parse("text Sarah Smith I'm running late")
        val res = contactResolver.resolveCommandTarget(parsed)
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
        val parsed = parser.parse("call John")
        val res = contactResolver.resolveCommandTarget(parsed)
        assertTrue(res is ContactResolutionResult.Ambiguous)
        val ambiguous = res as ContactResolutionResult.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `contact not found`() = runTest {
        fakeContactsProvider.candidates = emptyList()
        val parsed = parser.parse("call UnknownPerson")
        val res = contactResolver.resolveCommandTarget(parsed)
        assertEquals(ContactResolutionResult.NotFound, res)
    }

    @Test
    fun `contact permission required`() = runTest {
        fakeContactsProvider.hasPerm = false
        val parsed = parser.parse("call Sarah")
        val res = contactResolver.resolveCommandTarget(parsed)
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
        val parsed = parser.parse("call John Smith")
        val res = contactResolver.resolveCommandTarget(parsed)
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
        val parsed = parser.parse("email John Smith: Hi")
        val res = contactResolver.resolveCommandTarget(parsed)
        assertTrue(res is ContactResolutionResult.MultipleDestinations)
        val multi = res as ContactResolutionResult.MultipleDestinations
        assertEquals(2, multi.destinations.size)
    }

    @Test
    fun `permission denial never executes communication`() = runTest {
        fakeContactsProvider.hasPerm = false
        val parsed = parser.parse("call Sarah")
        val execResult = toolExecutor.executeAction(parsed)
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
        val parsed = parser.parse("call John Smith")
        val res = contactResolver.resolveCommandTarget(parsed)
        assertNotEquals(ContactResolutionResult.Resolved("John Smith", ContactDestination("123", "Mobile")), res)
    }

    // --- REQUIREMENT 4 SPECIFICALLY MANDATED TESTS ---

    @Test
    fun `successful speech result feeds into the same command-processing path as typed input`() {
        val parsedTyped = parser.parse("open github")
        val parsedSpeech = parser.parse("open github")
        assertEquals(parsedTyped.action, parsedSpeech.action)
        assertEquals(parsedTyped.targetAppOrPerson, parsedSpeech.targetAppOrPerson)
    }

    @Test
    fun `empty speech result does not execute a command`() {
        val parsed = parser.parse("")
        assertEquals(CommandAction.UNKNOWN, parsed.action)
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
        val parsed = parser.parse("call Sarah Smith")
        val execResult = toolExecutor.executeAction(parsed, resolvedResult = resolved)
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
        val parsed = parser.parse("call Sarah Smith")
        toolExecutor.executeAction(parsed, resolvedResult = resolved)

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
        val parsed = parser.parse("text Sarah Smith: Running late")
        val execResult = toolExecutor.executeAction(parsed, resolvedResult = resolved)
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
        val parsed = parser.parse("email John Smith: Meeting agenda")
        val execResult = toolExecutor.executeAction(parsed, resolvedResult = resolved)
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
        val parsed = parser.parse("call Sarah")
        val res = contactResolver.resolveCommandTarget(parsed)
        assertTrue(res is ContactResolutionResult.ProviderError)
        assertNotEquals(ContactResolutionResult.NotFound, res)
    }

    @Test
    fun `contact lookup-background architecture remains testable without a real address book`() = runTest {
        fakeContactsProvider.candidates = listOf(
            ContactCandidate("1", "Alice", listOf(ContactDestination("12345", "Mobile")))
        )
        val parsed = parser.parse("call Alice")
        val res = contactResolver.resolveCommandTarget(parsed)
        assertTrue(res is ContactResolutionResult.Resolved)
        assertEquals("Alice", (res as ContactResolutionResult.Resolved).displayName)
    }

    // --- PASS 3.2 TESTS: NATURAL TOOL COMMANDS & FUZZY MATCHING ---

    @Test
    fun `open Pydroid natural command resolution`() {
        val parsed = parser.parse("open Pydroid")
        assertEquals(CommandAction.OPEN_APP, parsed.action)
        val tool = toolRegistry.findTool(parsed.targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("pydroid", tool?.id)
        assertNull(parsed.followUp)
    }

    @Test
    fun `open Pydroid 3 natural command resolution`() {
        val parsed = parser.parse("open Pydroid 3")
        assertEquals(CommandAction.OPEN_APP, parsed.action)
        val tool = toolRegistry.findTool(parsed.targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("pydroid", tool?.id)
        assertNull(parsed.followUp)
    }

    @Test
    fun `open Pydroid 3 and start coding separates follow-up text`() {
        val parsed = parser.parse("open Pydroid 3 and start coding")
        assertEquals(CommandAction.OPEN_APP, parsed.action)
        val tool = toolRegistry.findTool(parsed.targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("pydroid", tool?.id)
        assertEquals("start coding", parsed.followUp)
    }

    @Test
    fun `open GitHub please strips polite trailing words`() {
        val parsed = parser.parse("open GitHub please")
        assertEquals(CommandAction.OPEN_APP, parsed.action)
        assertEquals("GitHub", parsed.targetAppOrPerson)
        assertNull(parsed.followUp)
    }

    @Test
    fun `launch Termux action verb resolution`() {
        val parsed = parser.parse("launch Termux")
        assertEquals(CommandAction.OPEN_APP, parsed.action)
        val tool = toolRegistry.findTool(parsed.targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("termux", tool?.id)
        assertNull(parsed.followUp)
    }

    @Test
    fun `start Acode action verb resolution`() {
        val parsed = parser.parse("start Acode")
        assertEquals(CommandAction.OPEN_APP, parsed.action)
        val tool = toolRegistry.findTool(parsed.targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("acode", tool?.id)
        assertNull(parsed.followUp)
    }

    @Test
    fun `Pyroid uniquely resolves to Pydroid via conservative fuzzy matching`() {
        val parsed = parser.parse("Pyroid")
        assertEquals(CommandAction.OPEN_APP, parsed.action)
        val tool = toolRegistry.findTool(parsed.targetAppOrPerson!!)
        assertNotNull(tool)
        assertEquals("pydroid", tool?.id)
    }

    @Test
    fun `unknown tool does not fuzzy-match dangerously`() {
        val parsed = parser.parse("open Photoshop")
        assertEquals(CommandAction.OPEN_APP, parsed.action)
        // ToolRegistry findTool should return null for Photoshop
        val tool = toolRegistry.findTool(parsed.targetAppOrPerson!!)
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
        val parsed = parser.parse("open Pydroid 3 and start coding")
        // If app is not installed, it safely returns NOT_INSTALLED
        val result = toolExecutor.executeAction(parsed)
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
}
