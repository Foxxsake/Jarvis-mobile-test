package com.example.engine.termux

enum class TermuxCommandCategory {
    FAST_DIAGNOSTIC,
    GIT_READ,
    NETWORK_GIT,
    TEST,
    BUILD,
    GENERAL
}

object TermuxTimeoutPolicy {
    const val TIMEOUT_FAST_DIAGNOSTIC_MS: Long = 10_000L     // 10 seconds
    const val TIMEOUT_GIT_READ_MS: Long = 30_000L            // 30 seconds
    const val TIMEOUT_NETWORK_GIT_MS: Long = 120_000L        // 2 minutes
    const val TIMEOUT_TEST_MS: Long = 300_000L               // 5 minutes
    const val TIMEOUT_BUILD_MS: Long = 300_000L              // 5 minutes
    const val TIMEOUT_GENERAL_MS: Long = 60_000L             // 1 minute

    fun determineCategory(executablePath: String, arguments: List<String>): TermuxCommandCategory {
        val exec = executablePath.substringAfterLast('/')
        val firstArg = arguments.firstOrNull()?.lowercase() ?: ""

        return when (exec) {
            "whoami", "pwd" -> TermuxCommandCategory.FAST_DIAGNOSTIC

            "git" -> when (firstArg) {
                "--version", "-v", "status", "diff", "log", "branch", "rev-parse" -> TermuxCommandCategory.GIT_READ
                "pull", "fetch", "push", "clone" -> TermuxCommandCategory.NETWORK_GIT
                else -> TermuxCommandCategory.GIT_READ
            }

            "npm" -> when (firstArg) {
                "--version", "-v", "list", "ls" -> TermuxCommandCategory.FAST_DIAGNOSTIC
                "test" -> TermuxCommandCategory.TEST
                "build" -> TermuxCommandCategory.BUILD
                "install", "ci" -> TermuxCommandCategory.BUILD
                else -> TermuxCommandCategory.GENERAL
            }

            "gradle", "gradlew", "./gradlew" -> {
                val hasTest = arguments.any { it.contains("test", ignoreCase = true) }
                val hasBuild = arguments.any { it.contains("assemble", ignoreCase = true) || it.contains("build", ignoreCase = true) }
                when {
                    hasTest -> TermuxCommandCategory.TEST
                    hasBuild -> TermuxCommandCategory.BUILD
                    firstArg == "--version" || firstArg == "-v" || firstArg == "tasks" -> TermuxCommandCategory.FAST_DIAGNOSTIC
                    else -> TermuxCommandCategory.BUILD
                }
            }

            "pytest" -> TermuxCommandCategory.TEST

            "node", "python", "python3" -> {
                if (firstArg == "--version" || firstArg == "-v") {
                    TermuxCommandCategory.FAST_DIAGNOSTIC
                } else {
                    TermuxCommandCategory.GENERAL
                }
            }

            else -> TermuxCommandCategory.GENERAL
        }
    }

    fun getTimeoutMs(request: TermuxCommandRequest): Long {
        if (request.timeoutMs != null) return request.timeoutMs
        val category = determineCategory(request.executablePath, request.arguments)
        return when (category) {
            TermuxCommandCategory.FAST_DIAGNOSTIC -> TIMEOUT_FAST_DIAGNOSTIC_MS
            TermuxCommandCategory.GIT_READ -> TIMEOUT_GIT_READ_MS
            TermuxCommandCategory.NETWORK_GIT -> TIMEOUT_NETWORK_GIT_MS
            TermuxCommandCategory.TEST -> TIMEOUT_TEST_MS
            TermuxCommandCategory.BUILD -> TIMEOUT_BUILD_MS
            TermuxCommandCategory.GENERAL -> TIMEOUT_GENERAL_MS
        }
    }
}
