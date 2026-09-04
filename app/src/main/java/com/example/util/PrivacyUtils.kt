package com.example.util

object PrivacyUtils {
    fun maskPhoneNumber(number: String): String {
        val digitsOnly = number.filter { it.isDigit() }
        if (digitsOnly.length <= 4) {
            return "****"
        }
        val visibleLastFour = digitsOnly.takeLast(4)
        val maskedCount = digitsOnly.length - 4
        return "*".repeat(maskedCount) + visibleLastFour
    }
}
