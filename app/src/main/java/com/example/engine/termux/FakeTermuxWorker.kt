package com.example.engine.termux

class FakeTermuxWorker(
    var mockConnectionStatus: TermuxConnectionStatus = TermuxConnectionStatus(
        isInstalled = true,
        isPermissionGranted = true,
        isExternalAppsAllowed = true,
        connectionState = TermuxConnectionState.TERMUX_READY
    )
) : TermuxWorker {

    val executedRequests = mutableListOf<TermuxCommandRequest>()

    override fun checkConnectionState(): TermuxConnectionStatus {
        return mockConnectionStatus
    }

    override suspend fun executeCommand(request: TermuxCommandRequest): TermuxExecutionResult {
        executedRequests.add(request)

        when (mockConnectionStatus.connectionState) {
            TermuxConnectionState.TERMUX_NOT_INSTALLED -> {
                return TermuxExecutionResult(
                    status = TermuxExecutionStatus.TERMUX_NOT_INSTALLED,
                    message = "Termux application is not installed on this device."
                )
            }
            TermuxConnectionState.TERMUX_PERMISSION_REQUIRED -> {
                return TermuxExecutionResult(
                    status = TermuxExecutionStatus.PERMISSION_REQUIRED,
                    message = "RUN_COMMAND permission is required to execute Termux actions."
                )
            }
            TermuxConnectionState.TERMUX_EXTERNAL_APPS_DISABLED -> {
                return TermuxExecutionResult(
                    status = TermuxExecutionStatus.SETUP_REQUIRED,
                    message = "External app execution is disabled in Termux settings (~/.termux/termux.properties)."
                )
            }
            TermuxConnectionState.TERMUX_READY -> { /* Proceed */ }
        }

        if (!TermuxCommandClassifier.isExecutableAllowed(request.executablePath)) {
            return TermuxExecutionResult(
                status = TermuxExecutionStatus.COMMAND_REJECTED,
                message = "Command '${request.executablePath}' is not supported or not allowed."
            )
        }

        val execName = request.executablePath.substringAfterLast('/')
        val startTime = System.currentTimeMillis()

        return when (execName) {
            "pwd" -> TermuxExecutionResult(
                status = TermuxExecutionStatus.SUCCESS,
                exitCode = 0,
                stdout = request.workingDirectory ?: "/data/data/com.termux/files/home",
                message = "Current directory: ${request.workingDirectory ?: "/data/data/com.termux/files/home"}",
                startTimeMillis = startTime,
                endTimeMillis = startTime + 10
            )
            "whoami" -> TermuxExecutionResult(
                status = TermuxExecutionStatus.SUCCESS,
                exitCode = 0,
                stdout = "u0_a245",
                message = "Termux user: u0_a245",
                startTimeMillis = startTime,
                endTimeMillis = startTime + 10
            )
            "git" -> {
                val arg = request.arguments.firstOrNull() ?: ""
                when (arg) {
                    "--version", "-v" -> TermuxExecutionResult(
                        status = TermuxExecutionStatus.SUCCESS,
                        exitCode = 0,
                        stdout = "git version 2.43.0",
                        message = "git version 2.43.0",
                        startTimeMillis = startTime,
                        endTimeMillis = startTime + 20
                    )
                    "status" -> TermuxExecutionResult(
                        status = TermuxExecutionStatus.SUCCESS,
                        exitCode = 0,
                        stdout = "On branch main\nYour branch is up to date with 'origin/main'.\nnothing to commit, working tree clean",
                        message = "Git status clean on branch main",
                        startTimeMillis = startTime,
                        endTimeMillis = startTime + 25
                    )
                    "branch" -> TermuxExecutionResult(
                        status = TermuxExecutionStatus.SUCCESS,
                        exitCode = 0,
                        stdout = "main",
                        message = "main",
                        startTimeMillis = startTime,
                        endTimeMillis = startTime + 10
                    )
                    else -> TermuxExecutionResult(
                        status = TermuxExecutionStatus.SUCCESS,
                        exitCode = 0,
                        stdout = "Executed git ${request.arguments.joinToString(" ")}",
                        message = "git command executed",
                        startTimeMillis = startTime,
                        endTimeMillis = startTime + 30
                    )
                }
            }
            "node" -> TermuxExecutionResult(
                status = TermuxExecutionStatus.SUCCESS,
                exitCode = 0,
                stdout = "v20.10.0",
                message = "node v20.10.0",
                startTimeMillis = startTime,
                endTimeMillis = startTime + 15
            )
            "npm" -> TermuxExecutionResult(
                status = TermuxExecutionStatus.SUCCESS,
                exitCode = 0,
                stdout = "10.2.3",
                message = "npm 10.2.3",
                startTimeMillis = startTime,
                endTimeMillis = startTime + 15
            )
            "python", "python3" -> TermuxExecutionResult(
                status = TermuxExecutionStatus.SUCCESS,
                exitCode = 0,
                stdout = "Python 3.11.6",
                message = "Python 3.11.6",
                startTimeMillis = startTime,
                endTimeMillis = startTime + 15
            )
            else -> TermuxExecutionResult(
                status = TermuxExecutionStatus.SUCCESS,
                exitCode = 0,
                stdout = "Executed ${request.executablePath} ${request.arguments.joinToString(" ")}",
                message = "Command executed successfully.",
                startTimeMillis = startTime,
                endTimeMillis = startTime + 15
            )
        }
    }
}
