package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.JarvisRuntime
import com.example.engine.speech.SpeechManager
import com.example.engine.termux.FakeTermuxWorker
import com.example.engine.termux.TermuxConnectionState
import com.example.engine.termux.TermuxConnectionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pass5A4SpeechAndTermuxVerificationTest {

    private lateinit var context: Context
    private lateinit var speechManager: SpeechManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        speechManager = SpeechManager(context)
    }

    @Test
    fun testTermuxProbeSetsVerifyingAndUpdatesRuntimeState() = runTest {
        val runtime = JarvisRuntime.getInstance(context)
        val result = runtime.probeTermuxConnection()

        assertNotNull(result.connectionState)
        assertEquals(result.connectionState, runtime.executionState.value.termuxStatus.connectionState)
    }

    @Test
    fun testSpeechManagerErrorNameMapping() {
        assertEquals("ERROR_NO_MATCH", SpeechManager.getSpeechErrorName(7))
        assertEquals("ERROR_RECOGNIZER_BUSY", SpeechManager.getSpeechErrorName(8))
        assertEquals("ERROR_SERVER", SpeechManager.getSpeechErrorName(4))
        assertEquals("ERROR_AUDIO", SpeechManager.getSpeechErrorName(3))
    }

    @Test
    fun testSpeechManagerInitialDiagnostics() {
        val diag = speechManager.diagnostics.value
        assertNotNull(diag.backend)
        assertEquals(null, diag.lastErrorCode)
        assertEquals(null, diag.lastErrorName)
    }

    @Test
    fun testSpeechSessionTokenVerification() {
        val initialToken = speechManager.currentSessionToken
        assertTrue(speechManager.verifySessionToken(initialToken))

        speechManager.resetState()
        val newToken = speechManager.currentSessionToken
        assertFalse(speechManager.verifySessionToken(initialToken))
        assertTrue(speechManager.verifySessionToken(newToken))
    }
}
