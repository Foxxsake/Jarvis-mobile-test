package com.example.engine.termux

object TermuxCommandClassifier {

    private val READ_ONLY_EXECUTABLES = setOf(
        "pwd", "whoami", "ls", "node", "npm", "python", "python3", "git"
    )

    private val ALLOWED_EXECUTABLES = setOf(
        "pwd", "whoami", "ls", "node", "npm", "python", "python3", "git", "gradle", "gradlew", "./gradlew", "rm"
    )

    fun classify(executable: String, args: List<String>): TermuxRiskLevel {
        val execName = executable.substringAfterLast('/')
        
        if (execName == "rm") return TermuxRiskLevel.DESTRUCTIVE
        
        if (execName == "git") {
            val subCommand = args.firstOrNull()?.lowercase() ?: ""
            return when (subCommand) {
                "status", "diff", "log", "branch", "rev-parse", "--version", "-v" -> TermuxRiskLevel.READ_ONLY
                "pull", "fetch", "checkout", "merge", "rebase", "add", "commit" -> TermuxRiskLevel.MUTATING
                "reset", "clean", "restore" -> TermuxRiskLevel.DESTRUCTIVE
                "push" -> TermuxRiskLevel.PUBLISHING
                else -> TermuxRiskLevel.MUTATING
            }
        }

        if (execName == "npm") {
            val subCommand = args.firstOrNull()?.lowercase() ?: ""
            return when (subCommand) {
                "--version", "-v", "list" -> TermuxRiskLevel.READ_ONLY
                "install", "ci", "build", "run", "test" -> TermuxRiskLevel.MUTATING
                "publish" -> TermuxRiskLevel.PUBLISHING
                else -> TermuxRiskLevel.MUTATING
            }
        }

        if (execName == "node" || execName == "python" || execName == "python3") {
            val firstArg = args.firstOrNull() ?: ""
            if (firstArg == "--version" || firstArg == "-v") {
                return TermuxRiskLevel.READ_ONLY
            }
            return TermuxRiskLevel.MUTATING
        }

        if (execName == "pwd" || execName == "whoami" || execName == "ls") {
            return TermuxRiskLevel.READ_ONLY
        }

        if (execName.contains("gradle")) {
            return TermuxRiskLevel.MUTATING
        }

        return TermuxRiskLevel.MUTATING
    }

    fun isExecutableAllowed(executable: String): Boolean {
        val execName = executable.substringAfterLast('/')
        return ALLOWED_EXECUTABLES.contains(execName)
    }

    fun requiresApproval(riskLevel: TermuxRiskLevel): Boolean {
        return riskLevel != TermuxRiskLevel.READ_ONLY
    }
}
