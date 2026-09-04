package com.example.engine

class ApprovalManager {
    fun requiresApproval(category: CommandCategory, action: String, rawText: String): Boolean {
        if (category == CommandCategory.DEVICE_ACTION) {
            return false // Opening apps is safe
        }
        
        if (category == CommandCategory.COMMUNICATION) {
            return true // Preparing texts/calls requires approval before passing to Intent
        }
        
        if (category == CommandCategory.DEVELOPMENT) {
            val text = rawText.lowercase()
            // Distinguish safe vs consequential
            if (text.startsWith("check") || text.startsWith("open") || text.startsWith("read")) {
                return false
            }
            if (text.contains("push") || text.contains("delete") || text.contains("overwrite") || text.contains("run")) {
                return true
            }
            return true // Default to safe-by-requiring-approval for unknown dev commands
        }
        
        return false // UNKNOWN etc shouldn't randomly approve, but the router handles them separately
    }
}
