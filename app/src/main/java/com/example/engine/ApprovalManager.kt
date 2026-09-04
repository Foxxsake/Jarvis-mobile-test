package com.example.engine

class ApprovalManager {
    fun requiresApproval(action: CommandAction, category: CommandCategory, rawText: String): Boolean {
        return when (action) {
            CommandAction.OPEN_APP,
            CommandAction.OPEN_SETTINGS,
            CommandAction.CHECK_GITHUB -> false

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
