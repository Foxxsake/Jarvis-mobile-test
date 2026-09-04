package com.example.engine

class ApprovalManager {
    fun requiresApproval(action: CommandAction, category: CommandCategory, rawText: String): Boolean {
        return when (action) {
            CommandAction.OPEN_APP,
            CommandAction.OPEN_SETTINGS,
            CommandAction.CHECK_GITHUB,
            CommandAction.CHECK_PROJECT_STATUS -> false

            CommandAction.TERMUX_COMMAND -> {
                val lower = rawText.lowercase().trim()
                if (lower == "check termux" || lower == "check git version" ||
                    lower == "check node version" || lower == "check npm version" ||
                    lower == "check python version" || lower == "check git status") {
                    false
                } else if (lower.startsWith("termux ")) {
                    val sub = lower.removePrefix("termux ").trim()
                    val parts = sub.split(" ")
                    val exec = parts.firstOrNull() ?: ""
                    val args = if (parts.size > 1) parts.subList(1, parts.size) else emptyList()
                    val risk = com.example.engine.termux.TermuxCommandClassifier.classify(exec, args)
                    com.example.engine.termux.TermuxCommandClassifier.requiresApproval(risk)
                } else if (lower == "run tests" || lower == "build project" || lower.startsWith("run test") || lower.startsWith("build ")) {
                    true
                } else {
                    true
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
