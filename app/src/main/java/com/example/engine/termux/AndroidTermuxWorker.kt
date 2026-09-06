package com.example.engine.termux

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class AndroidTermuxWorker(
    private val context: Context
) : TermuxWorker {

    @Volatile
    private var lastVerifiedState: TermuxConnectionState = TermuxConnectionState.UNVERIFIED

    @Volatile
    private var lastDetailMessage: String? = "Connection unverified. Tap 'Check Termux Connection' to probe."

    override fun checkConnectionState(): TermuxConnectionStatus {
        val (isInstalled, versionName) = getTermuxPackageInfo()

        if (!isInstalled) {
            lastVerifiedState = TermuxConnectionState.TERMUX_NOT_INSTALLED
            lastDetailMessage = "Termux application is not installed."
            return TermuxConnectionStatus(
                isInstalled = false,
                isPermissionGranted = false,
                isExternalAppsAllowed = false,
                connectionState = TermuxConnectionState.TERMUX_NOT_INSTALLED,
                termuxVersion = null,
                detailMessage = lastDetailMessage
            )
        }

        if (!isVersionSupported(versionName)) {
            lastVerifiedState = TermuxConnectionState.TERMUX_TOO_OLD
            lastDetailMessage = "Termux version $versionName is too old. Version ${TermuxConstants.MIN_REQUIRED_VERSION} or newer is required."
            return TermuxConnectionStatus(
                isInstalled = true,
                isPermissionGranted = false,
                isExternalAppsAllowed = false,
                connectionState = TermuxConnectionState.TERMUX_TOO_OLD,
                termuxVersion = versionName,
                detailMessage = lastDetailMessage
            )
        }

        val isPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            TermuxConstants.RUN_COMMAND_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED

        if (!isPermissionGranted) {
            lastVerifiedState = TermuxConnectionState.TERMUX_PERMISSION_REQUIRED
            lastDetailMessage = "RUN_COMMAND permission is required."
            return TermuxConnectionStatus(
                isInstalled = true,
                isPermissionGranted = false,
                isExternalAppsAllowed = false,
                connectionState = TermuxConnectionState.TERMUX_PERMISSION_REQUIRED,
                termuxVersion = versionName,
                detailMessage = lastDetailMessage
            )
        }

        val state = if (lastVerifiedState == TermuxConnectionState.READY ||
            lastVerifiedState == TermuxConnectionState.SETUP_REQUIRED ||
            lastVerifiedState == TermuxConnectionState.FAILED
        ) {
            lastVerifiedState
        } else {
            TermuxConnectionState.UNVERIFIED
        }

        return TermuxConnectionStatus(
            isInstalled = true,
            isPermissionGranted = true,
            isExternalAppsAllowed = (state == TermuxConnectionState.READY),
            connectionState = state,
            termuxVersion = versionName,
            detailMessage = lastDetailMessage
        )
    }

    override suspend fun probeConnection(): TermuxConnectionStatus {
        val currentStatus = checkConnectionState()
        if (currentStatus.connectionState == TermuxConnectionState.TERMUX_NOT_INSTALLED ||
            currentStatus.connectionState == TermuxConnectionState.TERMUX_TOO_OLD ||
            currentStatus.connectionState == TermuxConnectionState.TERMUX_PERMISSION_REQUIRED
        ) {
            return currentStatus
        }

        val probeRequest = TermuxCommandRequest(
            executablePath = "/data/data/com.termux/files/usr/bin/whoami",
            arguments = emptyList(),
            background = true,
            description = "Termux Connection Probe",
            riskLevel = TermuxRiskLevel.READ_ONLY
        )

        val probeResult = executeCommandInternal(probeRequest, isProbe = true)

        when (probeResult.status) {
            TermuxExecutionStatus.SUCCESS -> {
                lastVerifiedState = TermuxConnectionState.READY
                lastDetailMessage = "Termux command bridge verified working. (${probeResult.stdout.trim()})"
            }
            TermuxExecutionStatus.SETUP_REQUIRED -> {
                lastVerifiedState = TermuxConnectionState.SETUP_REQUIRED
                lastDetailMessage = probeResult.message
            }
            else -> {
                lastVerifiedState = TermuxConnectionState.FAILED
                lastDetailMessage = "Probe failed: ${probeResult.message}"
            }
        }

        return checkConnectionState()
    }

    override suspend fun executeCommand(request: TermuxCommandRequest): TermuxExecutionResult {
        return executeCommandInternal(request, isProbe = false)
    }

    private suspend fun executeCommandInternal(
        request: TermuxCommandRequest,
        isProbe: Boolean
    ): TermuxExecutionResult {
        if (!isProbe) {
            val connection = checkConnectionState()
            if (connection.connectionState == TermuxConnectionState.TERMUX_NOT_INSTALLED) {
                return TermuxExecutionResult(
                    status = TermuxExecutionStatus.TERMUX_NOT_INSTALLED,
                    message = "Termux application is not installed."
                )
            }
            if (connection.connectionState == TermuxConnectionState.TERMUX_TOO_OLD) {
                return TermuxExecutionResult(
                    status = TermuxExecutionStatus.TERMUX_TOO_OLD,
                    message = connection.detailMessage ?: "Termux version is too old."
                )
            }
            if (connection.connectionState == TermuxConnectionState.TERMUX_PERMISSION_REQUIRED) {
                return TermuxExecutionResult(
                    status = TermuxExecutionStatus.PERMISSION_REQUIRED,
                    message = "RUN_COMMAND permission is required for Termux execution."
                )
            }
        }

        if (!TermuxCommandClassifier.isExecutableAllowed(request.executablePath)) {
            return TermuxExecutionResult(
                status = TermuxExecutionStatus.COMMAND_REJECTED,
                message = "Command '${request.executablePath}' is not allowed or supported."
            )
        }

        val startTime = System.currentTimeMillis()
        val resultChannel = Channel<TermuxExecutionResult>(1)
        val executionId = UUID.randomUUID().toString()
        val requestCode = executionId.hashCode() and 0x00FFFFFF

        val callbackIntent = Intent(TermuxConstants.ACTION_RESULT_CALLBACK).apply {
            setPackage(context.packageName)
            putExtra("EXECUTION_ID", executionId)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_ONE_SHOT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            callbackIntent,
            flags
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                val recId = intent.getStringExtra("EXECUTION_ID")
                if (recId != null && recId != executionId) return

                val endTime = System.currentTimeMillis()
                val parsedResult = parseResultBundle(intent, startTime, endTime)
                resultChannel.trySend(parsedResult)
            }
        }

        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(TermuxConstants.ACTION_RESULT_CALLBACK),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val intent = Intent(TermuxConstants.ACTION_RUN_COMMAND).apply {
                setClassName(TermuxConstants.TERMUX_PACKAGE, TermuxConstants.RUN_COMMAND_SERVICE)
                putExtra(TermuxConstants.EXTRA_COMMAND_PATH, request.executablePath)
                if (request.arguments.isNotEmpty()) {
                    putExtra(TermuxConstants.EXTRA_ARGUMENTS, request.arguments.toTypedArray())
                }
                if (!request.workingDirectory.isNullOrBlank()) {
                    putExtra(TermuxConstants.EXTRA_WORKDIR, request.workingDirectory)
                }
                putExtra(TermuxConstants.EXTRA_BACKGROUND, request.background)
                if (request.description.isNotBlank()) {
                    putExtra(TermuxConstants.EXTRA_COMMAND_DESCRIPTION, request.description)
                }
                putExtra(TermuxConstants.EXTRA_PENDING_INTENT, pendingIntent)
            }

            val component = context.startService(intent)
            if (component == null) {
                return TermuxExecutionResult(
                    status = TermuxExecutionStatus.SETUP_REQUIRED,
                    message = "Termux RunCommandService could not be started. Verify Termux installation.",
                    startTimeMillis = startTime,
                    endTimeMillis = System.currentTimeMillis()
                )
            }

            val timeoutMs = TermuxTimeoutPolicy.getTimeoutMs(request)
            val result = withTimeoutOrNull(timeoutMs) {
                resultChannel.receive()
            }

            return result ?: TermuxExecutionResult(
                status = TermuxExecutionStatus.TIMED_OUT,
                message = "Termux command execution timed out after ${timeoutMs / 1000} seconds. Note: The process may continue running in Termux in the background.",
                startTimeMillis = startTime,
                endTimeMillis = System.currentTimeMillis()
            )

        } catch (e: SecurityException) {
            return TermuxExecutionResult(
                status = TermuxExecutionStatus.PERMISSION_REQUIRED,
                message = "SecurityException: com.termux.permission.RUN_COMMAND permission denied.",
                startTimeMillis = startTime,
                endTimeMillis = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            return TermuxExecutionResult(
                status = TermuxExecutionStatus.FAILED,
                message = "Execution failed: ${e.message}",
                startTimeMillis = startTime,
                endTimeMillis = System.currentTimeMillis()
            )
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    internal fun parseResultBundle(
        intent: Intent,
        startTime: Long,
        endTime: Long
    ): TermuxExecutionResult {
        val resultBundle = intent.getBundleExtra(TermuxConstants.EXTRA_PLUGIN_RESULT_BUNDLE)

        if (resultBundle == null) {
            return TermuxExecutionResult(
                status = TermuxExecutionStatus.FAILED,
                message = "Missing result bundle in Termux callback Intent.",
                startTimeMillis = startTime,
                endTimeMillis = endTime
            )
        }

        val stdout = resultBundle.getString(TermuxConstants.RESULT_BUNDLE_STDOUT) ?: ""
        val stderr = resultBundle.getString(TermuxConstants.RESULT_BUNDLE_STDERR) ?: ""
        val exitCode = if (resultBundle.containsKey(TermuxConstants.RESULT_BUNDLE_EXIT_CODE)) {
            resultBundle.getInt(TermuxConstants.RESULT_BUNDLE_EXIT_CODE, -1)
        } else {
            -1
        }

        val hasErr = resultBundle.containsKey(TermuxConstants.RESULT_BUNDLE_ERR)
        val errValue = if (hasErr) resultBundle.getInt(TermuxConstants.RESULT_BUNDLE_ERR) else null
        val errMsg = resultBundle.getString(TermuxConstants.RESULT_BUNDLE_ERR_MSG) ?: ""

        val isInternalSuccess = when {
            hasErr -> errValue == Activity.RESULT_OK
            else -> {
                // Backward compatibility: if err is absent, verify exitCode exists and errMsg is empty
                resultBundle.containsKey(TermuxConstants.RESULT_BUNDLE_EXIT_CODE) && errMsg.isBlank()
            }
        }

        val isSetupRequired = errMsg.contains("allow-external-apps", ignoreCase = true) ||
                errMsg.contains("external apps", ignoreCase = true) ||
                (errValue == 1 && errMsg.contains("disabled", ignoreCase = true))

        val status = when {
            isSetupRequired -> TermuxExecutionStatus.SETUP_REQUIRED
            !isInternalSuccess -> TermuxExecutionStatus.FAILED
            exitCode == 0 -> TermuxExecutionStatus.SUCCESS
            else -> TermuxExecutionStatus.FAILED
        }

        val message = when {
            status == TermuxExecutionStatus.SUCCESS -> stdout.trim().ifBlank { "Termux command bridge working." }
            status == TermuxExecutionStatus.SETUP_REQUIRED -> "Setup required: Ensure allow-external-apps=true in ~/.termux/termux.properties. Details: $errMsg"
            stderr.isNotBlank() -> stderr.trim()
            errMsg.isNotBlank() -> errMsg.trim()
            else -> "Command failed with exit code $exitCode"
        }

        return TermuxExecutionResult(
            status = status,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            message = message,
            startTimeMillis = startTime,
            endTimeMillis = endTime
        )
    }

    private fun getTermuxPackageInfo(): Pair<Boolean, String?> {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(TermuxConstants.TERMUX_PACKAGE, 0)
            Pair(true, pkgInfo.versionName)
        } catch (e: Exception) {
            Pair(false, null)
        }
    }

    private fun isVersionSupported(versionName: String?): Boolean {
        if (versionName.isNullOrBlank()) return true
        val parts = versionName.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return true
        val major = parts.getOrNull(0) ?: 0
        val minor = parts.getOrNull(1) ?: 0
        if (major > 0) return true
        return minor >= 109
    }
}

