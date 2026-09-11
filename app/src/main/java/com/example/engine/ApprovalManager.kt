package com.example.engine

import com.example.engine.termux.TermuxCommandClassifier
import com.example.engine.termux.TermuxRiskLevel

class ApprovalManager {
    fun requiresApproval(
        action: CommandAction,
        category: CommandCategory,
        rawText: String = "",
        riskLevel: TermuxRiskLevel? = null
    ): Boolean {
        return when (action) {
            CommandAction.OPEN_APP,
            CommandAction.OPEN_SETTINGS,
            CommandAction.CHECK_GITHUB,
            CommandAction.CHECK_PROJECT_STATUS -> false

            CommandAction.TERMUX_COMMAND -> {
                if (riskLevel != null) {
                    TermuxCommandClassifier.requiresApproval(riskLevel)
                } else {
                    val clean = rawText.removePrefix("termux ").trim()
                    val classifiedRisk = TermuxCommandClassifier.classifyCommandLine(clean)
                    TermuxCommandClassifier.requiresApproval(classifiedRisk)
                }
            }

            CommandAction.CALL,
            CommandAction.TEXT,
            CommandAction.EMAIL,
            CommandAction.PUSH,
            CommandAction.DELETE,
            CommandAction.OVERWRITE,
            CommandAction.RUN_COMMAND,
            CommandAction.BUILD,
            CommandAction.WORK_ON -> true

            // Phone control actions - read-only, no approval needed
            CommandAction.SET_VOLUME,
            CommandAction.SET_BRIGHTNESS,
            CommandAction.TOGGLE_WIFI,
            CommandAction.TOGGLE_BLUETOOTH,
            CommandAction.TOGGLE_FLASHLIGHT,
            CommandAction.PLAY_MEDIA,
            CommandAction.PAUSE_MEDIA,
            CommandAction.NEXT_TRACK,
            CommandAction.PREV_TRACK,
            CommandAction.SET_ALARM,
            CommandAction.SET_TIMER,
            CommandAction.SCREENSHOT,
            CommandAction.OPEN_URL,
            CommandAction.SEARCH_WEB,
            CommandAction.TAKE_PHOTO,
            CommandAction.DO_NOT_DISTURB_ON,
            CommandAction.DO_NOT_DISTURB_OFF -> false

            CommandAction.UNKNOWN -> false
        }
    }
}
