package com.example.engine.termux

enum class TermuxConnectionState {
    TERMUX_NOT_INSTALLED,
    TERMUX_TOO_OLD,
    TERMUX_PERMISSION_REQUIRED,
    UNVERIFIED,
    READY,
    SETUP_REQUIRED,
    FAILED
}

data class TermuxConnectionStatus(
    val isInstalled: Boolean,
    val isPermissionGranted: Boolean,
    val isExternalAppsAllowed: Boolean,
    val connectionState: TermuxConnectionState,
    val termuxVersion: String? = null,
    val detailMessage: String? = null
)

enum class TermuxRiskLevel {
    READ_ONLY,
    MUTATING,
    DESTRUCTIVE,
    PUBLISHING
}

enum class TermuxExecutionStatus {
    SUCCESS,
    FAILED,
    TERMUX_NOT_INSTALLED,
    TERMUX_TOO_OLD,
    PERMISSION_REQUIRED,
    SETUP_REQUIRED,
    COMMAND_REJECTED,
    NOT_SUPPORTED,
    TIMED_OUT,
    WORKSPACE_REQUIRED
}

data class TermuxCommandRequest(
    val executablePath: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val background: Boolean = true,
    val description: String = "",
    val riskLevel: TermuxRiskLevel = TermuxRiskLevel.READ_ONLY
)

data class TermuxExecutionResult(
    val status: TermuxExecutionStatus,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val message: String = "",
    val startTimeMillis: Long = System.currentTimeMillis(),
    val endTimeMillis: Long = System.currentTimeMillis()
)

