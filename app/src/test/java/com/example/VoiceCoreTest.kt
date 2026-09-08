package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.CommandAction
import com.example.engine.CommandCategory
import com.example.engine.CommandPlan
import com.example.engine.PlannedAction
import com.example.engine.ToolExecutionResult
import com.example.engine.ToolExecutionStatus
import com.example.engine.speech.SpeechManager
import com.example.engine.speech.SpeechState
import com.example.engine.voice.JarvisSpeechFormatter
import com.example.engine.voice.JarvisVoiceOutput
import com.example.engine.voice.VoiceOutputState
import com.example.engine.voice.VoiceSessionController
import com.example.engine.voice.VoiceSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class FakeVoiceOutput : JarvisVoiceOutput {
    private val _spokenTexts = mutableListOf<String>()
    val spokenTexts: List<String> get() = _spokenTexts

    private var onDoneCallback: (() -> Unit)? = null

    private val _state = MutableStateFlow<VoiceOutputState>(VoiceOutputState.Idle)
    override val state: StateFlow<VoiceOutputState> = _state.asStateFlow()

    override fun isAvailable(): Boolean = true

    override fun speak(text: String, onDone: (() -> Unit)?) {
        _spokenTexts.add(text)
        _state.value = VoiceOutputState.Speaking(text)
        this.onDoneCallback = onDone
    }

    override fun stop() {
        _state.value = VoiceOutputState.Idle
    }

    override fun shutdown() {
        _state.value = VoiceOutputState.Idle
    }

    fun finishSpeaking() {
        _state.value = VoiceOutputState.Idle
        onDoneCallback?.invoke()
        onDoneCallback = null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCoreTest {

    private lateinit var context: Context
    private lateinit var speechManager: SpeechManager
    private lateinit var fakeVoiceOutput: FakeVoiceOutput
    private lateinit var controller: VoiceSessionController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        speechManager = SpeechManager(context)
        fakeVoiceOutput = FakeVoiceOutput()
        controller = VoiceSessionController(
            speechManager = speechManager,
            voiceOutput = fakeVoiceOutput
        )
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(VoiceSessionState.IDLE, controller.state.value)
    }

    @Test
    fun testSpeechStateTransitions() {
        controller.handleSpeechState(SpeechState.Listening)
        assertEquals(VoiceSessionState.LISTENING, controller.state.value)

        controller.handleSpeechState(SpeechState.Processing)
        assertEquals(VoiceSessionState.TRANSCRIBING, controller.state.value)

        controller.handleSpeechState(SpeechState.Ready)
        assertEquals(VoiceSessionState.IDLE, controller.state.value)
    }

    @Test
    fun testSpeakResponseStateAndCompletion() {
        controller.setSpokenResponsesEnabled(true)
        controller.speakResponse("Opening Spotify.")

        assertEquals(VoiceSessionState.SPEAKING, controller.state.value)
        assertEquals(listOf("Opening Spotify."), fakeVoiceOutput.spokenTexts)

        fakeVoiceOutput.finishSpeaking()
        assertEquals(VoiceSessionState.IDLE, controller.state.value)
    }

    @Test
    fun testSpeakResponseMutedWhenSpokenResponsesDisabled() {
        controller.setSpokenResponsesEnabled(false)
        controller.speakResponse("Opening Spotify.")

        assertEquals(0, fakeVoiceOutput.spokenTexts.size)
        assertEquals(VoiceSessionState.IDLE, controller.state.value)
    }

    @Test
    fun testSpeechFormatterResults() {
        val successResult = ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Launched Spotify")
        val successSpeech = JarvisSpeechFormatter.formatExecutionResult("open spotify", successResult)
        assertEquals("Launched Spotify", successSpeech)

        val deniedResult = ToolExecutionResult(ToolExecutionStatus.PERMISSION_REQUIRED, "Need audio permission")
        val deniedSpeech = JarvisSpeechFormatter.formatExecutionResult("record audio", deniedResult)
        assertEquals("Permission required. Please grant access on screen.", deniedSpeech)

        val notInstalledResult = ToolExecutionResult(ToolExecutionStatus.NOT_INSTALLED, "App not installed")
        val notInstalledSpeech = JarvisSpeechFormatter.formatExecutionResult("open twitter", notInstalledResult)
        assertEquals("I can't find that app.", notInstalledSpeech)
    }

    @Test
    fun testSpeechFormatterApprovalPrompt() {
        val plan = CommandPlan(
            originalText = "run apt update",
            actions = listOf(
                PlannedAction(
                    action = CommandAction.TERMUX_COMMAND,
                    category = CommandCategory.DEVELOPMENT,
                    targetAppOrPerson = "termux",
                    requiresApproval = true
                )
            )
        )
        val prompt = JarvisSpeechFormatter.formatApprovalPrompt(plan, "Command: apt update")
        assertTrue(prompt.contains("Terminal command requires approval"))
    }

    @Test
    fun testHandsFreeModeEnablesAndDisables() {
        assertFalse(controller.isHandsFreeMode())
        controller.setHandsFreeMode(true)
        assertTrue(controller.isHandsFreeMode())
        controller.setHandsFreeMode(false)
        assertFalse(controller.isHandsFreeMode())
    }

    @Test
    fun testHandsFreeWaitingForApprovalPausesReArming() {
        controller.setHandsFreeMode(true)
        controller.onWaitingForApproval("Approval required")
        assertEquals(VoiceSessionState.SPEAKING, controller.state.value)

        fakeVoiceOutput.finishSpeaking()
        // Must stay in WAITING_FOR_APPROVAL and not automatically switch back to LISTENING
        assertEquals(VoiceSessionState.WAITING_FOR_APPROVAL, controller.state.value)
    }

    @Test
    fun testFeedbackLoopPreventionStopsListeningDuringSpeech() {
        controller.handleSpeechState(SpeechState.Listening)
        assertEquals(VoiceSessionState.LISTENING, controller.state.value)

        controller.speakResponse("Executing command")
        // Speaking should take precedence and recognizer should be stopped
        assertEquals(VoiceSessionState.SPEAKING, controller.state.value)
    }
}
