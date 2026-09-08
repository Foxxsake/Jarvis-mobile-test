package com.example.util

object PrivacyUtils {

    private val TOKEN_REGEX = Regex("(?i)(api[_-]?key|bearer|token|secret|password|pwd|auth)\\s*[:=]\\s*['\"]?([^'\"\\s]+)['\"]?")
    private val PHONE_NUMBER_REGEX = Regex("(\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}")
    private val EMAIL_REGEX = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")

    fun maskPhoneNumber(number: String): String {
        val digitsOnly = number.filter { it.isDigit() }
        if (digitsOnly.length <= 4) {
            return "****"
        }
        val visibleLastFour = digitsOnly.takeLast(4)
        val maskedCount = digitsOnly.length - 4
        return "*".repeat(maskedCount) + visibleLastFour
    }

    fun maskEmail(email: String): String {
        val parts = email.split("@")
        if (parts.size != 2) return "***@***"
        val name = parts[0]
        val domain = parts[1]
        val maskedName = if (name.length <= 2) {
            "*"
        } else {
            name.first() + "*".repeat(name.length - 2) + name.last()
        }
        return "$maskedName@$domain"
    }

    fun redactSensitiveText(text: String): String {
        if (text.isBlank()) return text
        var sanitized = text

        // Redact tokens/passwords/api keys
        sanitized = TOKEN_REGEX.replace(sanitized) { matchResult ->
            val key = matchResult.groupValues[1]
            "$key=[REDACTED]"
        }

        // Redact communication message bodies in commands like "text Alice: secret msg" or "email Bob: secret msg"
        val lower = sanitized.lowercase()
        if ((lower.startsWith("text ") || lower.startsWith("email ") || lower.startsWith("call ")) && sanitized.contains(":")) {
            val prefix = sanitized.substringBefore(":")
            val colonIdx = sanitized.indexOf(":")
            val afterColon = sanitized.substring(colonIdx + 1).trim()
            if (afterColon.isNotEmpty()) {
                sanitized = "$prefix: [REDACTED MESSAGE]"
            }
        }

        return sanitized
    }

    fun sanitizeForLog(
        rawCommand: String,
        targetAppOrPerson: String? = null
    ): String {
        var clean = redactSensitiveText(rawCommand)
        
        // Also mask raw phone numbers in the command if present
        clean = PHONE_NUMBER_REGEX.replace(clean) { match ->
            maskPhoneNumber(match.value)
        }
        
        // Also mask raw emails if present
        clean = EMAIL_REGEX.replace(clean) { match ->
            maskEmail(match.value)
        }

        return clean
    }
}
