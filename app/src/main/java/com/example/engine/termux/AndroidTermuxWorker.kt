package com.example.engine.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

class AndroidTermuxWorker(
    private val context: Context
) : TermuxWorker {

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val ACTION_RESULT_CALLBACK = "com.example.jarvis.TERMUX_RESULT_CALLBACK"

        const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_DESCRIPTION = "com.termux.RUN_COMMAND_DESCRIPTION"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        const val RESULT_STDOUT = "stdout"
        const val RESULT_STDERR = "stderr"
        const val RESULT_EXIT_CODE = "exitCode"
        const val RESULT_ERR_CODE = "errCode"
        const val RESULT_ERR_MSG = "errmsg"
    }

    override fun checkConnectionState(): TermuxConnectionStatus {
        val isInstalled = try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }

        val isPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            RUN_COMMAND_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED

        val connectionState = when {
            !isInstalled -> TermuxConnectionState.TERMUX_NOT_INSTALLED
            !isPermissionGranted -> TermuxConnectionState.TERMUX_PERMISSION_REQUIRED
            else -> TermuxConnectionState.TERMUX_READY
        }

        return TermuxConnectionStatus(
            isInstalled = isInstalled,
            isPermissionGranted = isPermissionGranted,
            isExternalAppsAllowed = isPermissionGranted && isInstalled,
            connectionState = connectionState
        )
    }

    override suspend fun executeCommand(request: TermuxCommandRequest): TermuxExecutionResult {
        val connection = checkConnectionState()
        if (connection.connectionState != TermuxConnectionState.TERMUX_READY) {
            return when (connection.connectionState) {
                TermuxConnectionState.TERMUX_NOT_INSTALLED -> TermuxExecutionResult(
                    status = TermuxExecutionStatus.TERMUX_NOT_INSTALLED,
                    message = "Termux application is not installed."
                )
                TermuxConnectionState.TERMUX_PERMISSION_REQUIRED -> TermuxExecutionResult(
                    status = TermuxExecutionStatus.PERMISSION_REQUIRED,
                    message = "RUN_COMMAND permission is required for Termux execution."
                )
                TermuxConnectionState.TERMUX_EXTERNAL_APPS_DISABLED -> TermuxExecutionResult(
                    status = TermuxExecutionStatus.SETUP_REQUIRED,
                    message = "External app execution is disabled in Termux settings."
                )
                else -> TermuxExecutionResult(
                    status = TermuxExecutionStatus.FAILED,
                    message = "Termux connection is not ready."
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
        val requestCode = (1000..9999).random()

        val callbackIntent = Intent(ACTION_RESULT_CALLBACK).apply {
            `package` = context.packageName
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
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
                val stdout = intent.getStringExtra(RESULT_STDOUT) ?: ""
                val stderr = intent.getStringExtra(RESULT_STDERR) ?: ""
                val exitCode = intent.getIntExtra(RESULT_EXIT_CODE, -1)
                val errCode = intent.getIntExtra(RESULT_ERR_CODE, 0)
                val errMsg = intent.getStringExtra(RESULT_ERR_MSG) ?: ""

                val endTime = System.currentTimeMillis()

                val status = when {
                    errCode != 0 -> {
                        if (errMsg.contains("external apps", ignoreCase = true) || errCode == 1) {
                            TermuxExecutionStatus.SETUP_REQUIRED
                        } else {
                            TermuxExecutionStatus.FAILED
                        }
                    }
                    exitCode == 0 -> TermuxExecutionStatus.SUCCESS
                    else -> TermuxExecutionStatus.FAILED
                }

                val msg = when {
                    status == TermuxExecutionStatus.SUCCESS -> stdout.ifBlank { "Command completed successfully." }
                    status == TermuxExecutionStatus.SETUP_REQUIRED -> "Setup required: Ensure allow-external-apps=true is in ~/.termux/termux.properties"
                    stderr.isNotBlank() -> stderr
                    errMsg.isNotBlank() -> errMsg
                    else -> "Command failed with exit code $exitCode"
                }

                resultChannel.trySend(
                    TermuxExecutionResult(
                        status = status,
                        exitCode = exitCode,
                        stdout = stdout,
                        stderr = stderr,
                        message = msg,
                        startTimeMillis = startTime,
                        endTimeMillis = endTime
                    )
                )
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_RESULT_CALLBACK),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra(EXTRA_PATH, request.executablePath)
                putExtra(EXTRA_ARGUMENTS, request.arguments.toTypedArray())
                if (request.workingDirectory != null) {
                    putExtra(EXTRA_WORKDIR, request.workingDirectory)
                }
                putExtra(EXTRA_BACKGROUND, request.background)
                putExtra(EXTRA_DESCRIPTION, request.description)
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }

            val component = context.startService(intent)
            if (component == null) {
                context.unregisterReceiver(receiver)
                return TermuxExecutionResult(
                    status = TermuxExecutionStatus.SETUP_REQUIRED,
                    message = "Termux RunCommandService could not be started. Check Termux installation."
                )
            }

            val result = withTimeoutOrNull(10000L) {
                resultChannel.receive()
            }

            context.unregisterReceiver(receiver)

            return result ?: TermuxExecutionResult(
                status = TermuxExecutionStatus.TIMED_OUT,
                message = "Termux command execution timed out after 10 seconds.",
                startTimeMillis = startTime,
                endTimeMillis = System.currentTimeMillis()
            )

        } catch (e: SecurityException) {
            context.unregisterReceiver(receiver)
            return TermuxExecutionResult(
                status = TermuxExecutionStatus.PERMISSION_REQUIRED,
                message = "SecurityException: com.termux.permission.RUN_COMMAND permission denied."
            )
        } catch (e: Exception) {
            context.unregisterReceiver(receiver)
            return TermuxExecutionResult(
                status = TermuxExecutionStatus.FAILED,
                message = "Execution failed: ${e.message}"
            )
        }
    }
}
