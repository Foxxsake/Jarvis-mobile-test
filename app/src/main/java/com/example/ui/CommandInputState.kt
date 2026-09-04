package com.example.ui

/**
 * Manages the state of the command text input field, ensuring that speech recognition
 * results populate the field exactly once per recognition event, while giving the user
 * complete manual control to edit, delete, clear, or replace text without recomposition bugs.
 */
class CommandInputState(
    initialText: String = ""
) {
    var text: String = initialText
        private set

    var lastHandledSpeechEventId: Long = 0L
        private set

    /**
     * Called when a speech recognition event arrives.
     * Returns true if the field was populated, or false if this event ID was already processed.
     */
    fun onSpeechResult(eventId: Long, recognizedText: String): Boolean {
        if (eventId != 0L && eventId != lastHandledSpeechEventId && recognizedText.isNotBlank()) {
            lastHandledSpeechEventId = eventId
            text = recognizedText
            return true
        }
        return false
    }

    /**
     * Simulates a Compose recomposition cycle where the parent UI state still contains
     * the previously recognized speech text and event ID. Recomposition must NEVER
     * overwrite text the user has manually typed, edited, or cleared.
     */
    fun onRecompose(currentSpeechEventId: Long, currentSpeechText: String) {
        // Deliberate no-op: local input state is not overwritten during normal recomposition
    }

    fun onUserTextChange(newText: String) {
        text = newText
    }

    fun deleteLastChar() {
        if (text.isNotEmpty()) {
            text = text.dropLast(1)
        }
    }

    fun clear() {
        text = ""
    }

    fun submit(): String {
        val submitted = text
        text = ""
        return submitted
    }
}
