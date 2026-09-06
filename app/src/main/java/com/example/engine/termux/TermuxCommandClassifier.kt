package com.example.engine.termux

object TermuxCommandClassifier {

    private val ALLOWED_EXECUTABLES = setOf(
        "pwd", "whoami", "ls", "node", "npm", "python", "python3", "git",
        "gradle", "gradlew", "./gradlew", "rm", "rmdir", "cat", "head", "tail",
        "grep", "find", "which", "pytest"
    )

    fun classify(executable: String, args: List<String>): TermuxRiskLevel {
        val execName = executable.substringAfterLast('/')

        if (execName == "rm" || execName == "rmdir") {
            return TermuxRiskLevel.DESTRUCTIVE
        }

        if (execName == "git") {
            return classifyGit(args)
        }

        if (execName == "npm") {
            return classifyNpm(args)
        }

        if (execName == "gradle" || execName == "gradlew" || execName == "./gradlew") {
            val firstArg = args.firstOrNull()?.lowercase() ?: ""
            if (firstArg == "--version" || firstArg == "-v" || firstArg == "tasks") {
                return TermuxRiskLevel.READ_ONLY
            }
            return TermuxRiskLevel.MUTATING
        }

        if (execName == "pytest") {
            return TermuxRiskLevel.MUTATING
        }

        if (execName == "node" || execName == "python" || execName == "python3") {
            val firstArg = args.firstOrNull()?.lowercase() ?: ""
            if (firstArg == "--version" || firstArg == "-v") {
                return TermuxRiskLevel.READ_ONLY
            }
            return TermuxRiskLevel.MUTATING
        }

        if (execName in setOf("pwd", "whoami", "ls", "cat", "head", "tail", "grep", "find", "which")) {
            return TermuxRiskLevel.READ_ONLY
        }

        return TermuxRiskLevel.MUTATING
    }

    fun classifyCommandLine(commandLine: String): TermuxRiskLevel {
        val parts = commandLine.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.isEmpty()) return TermuxRiskLevel.READ_ONLY
        return classify(parts[0], parts.drop(1))
    }

    private fun classifyGit(args: List<String>): TermuxRiskLevel {
        if (args.isEmpty()) return TermuxRiskLevel.READ_ONLY
        val subCommand = args[0].lowercase()
        val rest = args.drop(1)

        return when (subCommand) {
            "--version", "-v" -> TermuxRiskLevel.READ_ONLY

            "status", "diff", "log", "rev-parse" -> TermuxRiskLevel.READ_ONLY

            "remote" -> {
                if (rest.isEmpty() || (rest.size == 1 && (rest[0] == "-v" || rest[0] == "--verbose"))) {
                    TermuxRiskLevel.READ_ONLY
                } else {
                    TermuxRiskLevel.MUTATING
                }
            }

            "branch" -> {
                val hasDeleteFlag = rest.any { it == "-d" || it == "-D" || it == "--delete" }
                if (hasDeleteFlag) {
                    return TermuxRiskLevel.DESTRUCTIVE
                }
                val hasMoveFlag = rest.any { it == "-m" || it == "-M" || it == "--move" || it == "-c" || it == "-C" }
                if (hasMoveFlag) {
                    return TermuxRiskLevel.MUTATING
                }
                val readOnlyFlags = setOf("-a", "-r", "--all", "--remotes", "--list", "--show-current", "-v", "--verbose")
                val isAllReadOnlyFlags = rest.all { it in readOnlyFlags }
                if (rest.isEmpty() || isAllReadOnlyFlags) {
                    TermuxRiskLevel.READ_ONLY
                } else {
                    // Creating or modifying branch: git branch new-feature
                    TermuxRiskLevel.MUTATING
                }
            }

            "checkout" -> {
                // checkout -- <file> or checkout -- . discards working tree changes
                val hasDiscardFlag = rest.any { it == "--" } || rest.any { it == "." }
                if (hasDiscardFlag) {
                    TermuxRiskLevel.DESTRUCTIVE
                } else {
                    TermuxRiskLevel.MUTATING
                }
            }

            "restore" -> {
                if (rest.any { it == "--staged" }) {
                    TermuxRiskLevel.MUTATING
                } else {
                    TermuxRiskLevel.DESTRUCTIVE
                }
            }

            "switch" -> {
                if (rest.any { it == "-C" || it == "--force-create" }) {
                    TermuxRiskLevel.DESTRUCTIVE
                } else {
                    TermuxRiskLevel.MUTATING
                }
            }

            "clean" -> {
                if (rest.any { it == "-n" || it == "--dry-run" }) {
                    TermuxRiskLevel.READ_ONLY
                } else {
                    TermuxRiskLevel.DESTRUCTIVE
                }
            }

            "reset" -> {
                if (rest.any { it == "--hard" }) {
                    TermuxRiskLevel.DESTRUCTIVE
                } else {
                    TermuxRiskLevel.MUTATING
                }
            }

            "push" -> TermuxRiskLevel.PUBLISHING

            "pull", "fetch", "merge", "rebase", "add", "commit" -> TermuxRiskLevel.MUTATING

            else -> TermuxRiskLevel.MUTATING
        }
    }

    private fun classifyNpm(args: List<String>): TermuxRiskLevel {
        if (args.isEmpty()) return TermuxRiskLevel.READ_ONLY
        val sub = args[0].lowercase()
        return when (sub) {
            "--version", "-v", "list", "ls" -> TermuxRiskLevel.READ_ONLY
            "publish" -> TermuxRiskLevel.PUBLISHING
            "install", "ci", "build", "run", "test" -> TermuxRiskLevel.MUTATING
            else -> TermuxRiskLevel.MUTATING
        }
    }

    fun isExecutableAllowed(executable: String): Boolean {
        val execName = executable.substringAfterLast('/')
        return ALLOWED_EXECUTABLES.contains(execName)
    }

    fun requiresApproval(riskLevel: TermuxRiskLevel): Boolean {
        return riskLevel != TermuxRiskLevel.READ_ONLY
    }
}
