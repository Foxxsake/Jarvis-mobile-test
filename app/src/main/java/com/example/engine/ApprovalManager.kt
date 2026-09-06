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

            CommandAction.UNKNOWN -> false
        }
    }
}
