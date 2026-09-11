package com.example.engine.ai

import android.util.Log
import com.example.engine.CommandAction
import com.example.engine.CommandCategory
import com.example.engine.CommandPlan
import com.example.engine.PlannedAction
import com.example.engine.termux.TermuxRiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini AI provider that sends unrecognized commands to Google's Gemini API
 * for intelligent parsing and response generation.
 *
 * The system prompt instructs Gemini to return structured JSON that maps
 * user intents to the app's available CommandActions.
 */
class GeminiProvider(private val apiKey: String) : AIProvider {

    companion object {
        private const val TAG = "GeminiProvider"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

        private val SYSTEM_PROMPT = """You are JARVIS, an AI assistant that controls an Android phone. You receive voice commands and must return structured JSON responses.

AVAILABLE ACTIONS (return one of these in your "action" field):
- OPEN_APP: Launch an app (target: app name)
- CALL: Make a phone call (target: person name)
- TEXT: Send an SMS (target: person name, message: text)
- EMAIL: Send an email (target: email address, message: text)
- SET_VOLUME: Control volume (args: "up", "down", "mute", "unmute", or a number 0-100)
- SET_BRIGHTNESS: Control screen brightness (args: "up", "down", or a number 0-100)
- TOGGLE_WIFI: Toggle WiFi (args: "on", "off", "toggle")
- TOGGLE_BLUETOOTH: Toggle Bluetooth (args: "on", "off", "toggle")
- TOGGLE_FLASHLIGHT: Toggle flashlight (args: "on", "off", "toggle")
- PLAY_MEDIA: Play/resume music
- PAUSE_MEDIA: Pause music
- NEXT_TRACK: Skip to next song
- PREV_TRACK: Go to previous song
- SET_ALARM: Set an alarm (args: "HH:MM" in 24h format)
- SET_TIMER: Set a timer (args: duration in seconds as string)
- SCREENSHOT: Take a screenshot
- OPEN_URL: Open a website (args: URL)
- SEARCH_WEB: Search the web (args: search query)
- TAKE_PHOTO: Open camera to take a photo
- DO_NOT_DISTURB_ON: Enable Do Not Disturb
- DO_NOT_DISTURB_OFF: Disable Do Not Disturb
- OPEN_SETTINGS: Open phone settings
- TERMUX_COMMAND: Run a terminal command (args: command string)

RESPONSE FORMAT (return ONLY valid JSON, no markdown):
{
  "action": "ACTION_NAME",
  "args": "arguments string",
  "target": "target if applicable",
  "message": "message if applicable",
  "response": "spoken response to the user"
}

EXAMPLES:
User: "volume up" -> {"action": "SET_VOLUME", "args": "up", "response": "Volume increased."}
User: "call John" -> {"action": "CALL", "target": "John", "response": "Calling John."}
User: "what's the weather" -> {"action": "SEARCH_WEB", "args": "weather today", "response": "Searching for the weather."}
User: "open YouTube" -> {"action": "OPEN_APP", "target": "YouTube", "response": "Opening YouTube."}
User: "set alarm for 7am" -> {"action": "SET_ALARM", "args": "07:00", "response": "Alarm set for 7 AM."}
User: "turn on the flashlight" -> {"action": "TOGGLE_FLASHLIGHT", "args": "on", "response": "Flashlight turned on."}
User: "play some music" -> {"action": "PLAY_MEDIA", "response": "Playing music."}
User: "who won the super bowl" -> {"action": "SEARCH_WEB", "args": "who won the super bowl", "response": "Let me search that for you."}
User: "set a timer for 5 minutes" -> {"action": "SET_TIMER", "args": "300", "response": "Timer set for 5 minutes."}
User: "dim the screen" -> {"action": "SET_BRIGHTNESS", "args": "down", "response": "Dimming the screen."}
User: "turn on do not disturb" -> {"action": "DO_NOT_DISTURB_ON", "response": "Do Not Disturb enabled."}
User: "text Sarah: running late" -> {"action": "TEXT", "target": "Sarah", "message": "running late", "response": "Texting Sarah."}

Always return valid JSON. If unsure, default to SEARCH_WEB with the original query."""
    }

    override suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "$SYSTEM_PROMPT\n\nUser command: $prompt")
                            })
                        })
                    })
                })
                put("generationSettings", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 512)
                })
            }

            val url = URL("$BASE_URL?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "Gemini API error ($responseCode): $errorStream")
                return@withContext "I'm sorry, I couldn't process that command. (API error: $responseCode)"
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(responseBody)

            // Extract text from Gemini response
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val text = parts.getJSONObject(0).optString("text", "")
                    return@withContext text.trim()
                }
            }

            "I'm sorry, I couldn't understand that command."
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API call failed", e)
            "I'm sorry, I'm having trouble connecting to the AI service. Please try again."
        }
    }

    /**
     * Parses a Gemini JSON response into a CommandPlan.
     * Returns null if parsing fails, allowing the caller to fall back to UNKNOWN.
     */
    fun parseResponseToPlan(responseText: String, originalText: String): CommandPlan? {
        return try {
            // Try to extract JSON from the response (it might be wrapped in markdown)
            val jsonStr = extractJson(responseText) ?: return null
            val json = JSONObject(jsonStr)

            val actionStr = json.optString("action", "UNKNOWN")
            val args = json.optString("args", null)
            val target = json.optString("target", null)
            val message = json.optString("message", null)
            val spokenResponse = json.optString("response", null)

            val action = try {
                CommandAction.valueOf(actionStr)
            } catch (_: Exception) {
                CommandAction.UNKNOWN
            }

            if (action == CommandAction.UNKNOWN) return null

            val plannedAction = PlannedAction(
                action = action,
                category = when (action) {
                    CommandAction.OPEN_APP, CommandAction.OPEN_SETTINGS -> CommandCategory.DEVICE_ACTION
                    CommandAction.CALL, CommandAction.TEXT, CommandAction.EMAIL -> CommandCategory.COMMUNICATION
                    CommandAction.TERMUX_COMMAND, CommandAction.BUILD, CommandAction.PUSH -> CommandCategory.DEVELOPMENT
                    else -> CommandCategory.DEVICE_ACTION
                },
                targetAppOrPerson = target,
                rawArguments = args,
                messageOrQuery = message,
                requiresApproval = false,
                riskLevel = if (action == CommandAction.TERMUX_COMMAND) TermuxRiskLevel.READ_ONLY else null,
                proposal = null
            )

            CommandPlan(
                originalText = originalText,
                actions = listOf(plannedAction)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini response", e)
            null
        }
    }

    /**
     * Extracts JSON from a response that might contain markdown code blocks or other text.
     */
    private fun extractJson(text: String): String? {
        // Try to find JSON in code blocks
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        codeBlockPattern.find(text)?.let { match ->
            return match.groupValues[1].trim()
        }

        // Try to find raw JSON
        val jsonPattern = Regex("\\{[^{}]*\"action\"[^{}]*}", RegexOption.DOT_MATCHES_ALL)
        jsonPattern.find(text)?.let { match ->
            return match.value.trim()
        }

        // If the whole text looks like JSON
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        return null
    }
}
