package com.example.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.engine.contacts.ContactResolutionResult
import com.example.engine.phone.PhoneController
import com.example.util.PrivacyUtils

enum class ToolExecutionStatus {
    SUCCESS,
    NOT_INSTALLED,
    UNSUPPORTED,
    NOT_IMPLEMENTED,
    CONTACT_RESOLUTION_REQUIRED,
    REQUIRES_CONNECTION,
    FAILED,
    PERMISSION_REQUIRED,
    SETUP_REQUIRED,
    TIMED_OUT,
    WORKSPACE_REQUIRED,
    COMMAND_REJECTED,
    NOT_SUPPORTED,
    SKIPPED,
    AMBIGUOUS_APP
}

data class ToolExecutionResult(
    val status: ToolExecutionStatus,
    val message: String,
    val candidateTools: List<Tool> = emptyList()
)

class ToolExecutor(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val contactResolver: ContactResolver,
    private val termuxWorker: com.example.engine.termux.TermuxWorker = com.example.engine.termux.AndroidTermuxWorker(context),
    private val workspaceRegistry: com.example.data.workspace.WorkspaceRegistry = com.example.data.workspace.LocalWorkspaceRegistry(context),
    private val phoneController: PhoneController = PhoneController(context)
) {
    suspend fun executeAction(
        command: PlannedAction,
        resolvedResult: ContactResolutionResult? = null,
        isLocalProcessingEnabled: Boolean = true,
        authorization: com.example.engine.policy.ExecutionAuthorization = com.example.engine.policy.ExecutionAuthorization.untrusted()
    ): ToolExecutionResult {
        if (!isLocalProcessingEnabled) {
            return ToolExecutionResult(
                ToolExecutionStatus.FAILED,
                "Local command processing is currently disabled in settings."
            )
        }

        val policyDecision = com.example.engine.policy.ExecutionPolicyGuard.evaluate(
            action = command,
            toolRegistry = toolRegistry,
            isApprovedByUser = authorization.isApprovedByUser
        )
        when (policyDecision) {
            is com.example.engine.policy.PolicyDecision.Blocked -> {
                return ToolExecutionResult(ToolExecutionStatus.FAILED, policyDecision.reason)
            }
            is com.example.engine.policy.PolicyDecision.RequiresApproval -> {
                return ToolExecutionResult(
                    ToolExecutionStatus.COMMAND_REJECTED,
                    "Execution blocked: ${policyDecision.reason} Explicit user approval required."
                )
            }
            is com.example.engine.policy.PolicyDecision.Allowed -> {
                // Allowed to execute
            }
        }

        return when (command.action) {
            CommandAction.OPEN_SETTINGS -> handleOpenSettings()
            CommandAction.OPEN_APP -> handleOpenApp(command)
            CommandAction.CALL,
            CommandAction.TEXT,
            CommandAction.EMAIL -> handleCommunication(command, resolvedResult)
            CommandAction.CHECK_GITHUB -> handleCheckGithub(command)
            CommandAction.CHECK_PROJECT_STATUS -> handleCheckProjectStatus()
            CommandAction.TERMUX_COMMAND -> handleTermuxCommand(command)
            CommandAction.BUILD,
            CommandAction.WORK_ON,
            CommandAction.PUSH,
            CommandAction.DELETE,
            CommandAction.OVERWRITE,
            CommandAction.RUN_COMMAND -> handleDevelopmentAction(command)
            // Phone control actions
            CommandAction.SET_VOLUME -> handleSetVolume(command)
            CommandAction.SET_BRIGHTNESS -> handleSetBrightness(command)
            CommandAction.TOGGLE_WIFI -> handleToggleWifi(command)
            CommandAction.TOGGLE_BLUETOOTH -> handleToggleBluetooth(command)
            CommandAction.TOGGLE_FLASHLIGHT -> handleToggleFlashlight(command)
            CommandAction.PLAY_MEDIA -> handlePlayMedia()
            CommandAction.PAUSE_MEDIA -> handlePauseMedia()
            CommandAction.NEXT_TRACK -> handleNextTrack()
            CommandAction.PREV_TRACK -> handlePrevTrack()
            CommandAction.SET_ALARM -> handleSetAlarm(command)
            CommandAction.SET_TIMER -> handleSetTimer(command)
            CommandAction.SCREENSHOT -> handleScreenshot()
            CommandAction.OPEN_URL -> handleOpenUrl(command)
            CommandAction.SEARCH_WEB -> handleSearchWeb(command)
            CommandAction.TAKE_PHOTO -> handleTakePhoto()
            CommandAction.DO_NOT_DISTURB_ON -> handleDoNotDisturb(true)
            CommandAction.DO_NOT_DISTURB_OFF -> handleDoNotDisturb(false)
            CommandAction.UNKNOWN -> ToolExecutionResult(
                ToolExecutionStatus.NOT_IMPLEMENTED,
                "Command not recognized locally. Requires AI engine."
            )
        }
    }

    private fun truncatePreview(text: String, maxChars: Int = 400): String {
        val trimmed = text.trim()
        return if (trimmed.length > maxChars) {
            trimmed.take(maxChars) + "\n... [truncated ${trimmed.length - maxChars} chars]"
        } else {
            trimmed
        }
    }

    // --- Phone control handlers ---

    private fun handleSetVolume(command: PlannedAction): ToolExecutionResult {
        val arg = command.rawArguments?.lowercase() ?: return ToolExecutionResult(ToolExecutionStatus.FAILED, "Volume level not specified")
        return when (arg) {
            "up" -> mapPhoneResult(phoneController.volumeUp())
            "down" -> mapPhoneResult(phoneController.volumeDown())
            "mute" -> mapPhoneResult(phoneController.mute())
            "unmute" -> mapPhoneResult(phoneController.unmute())
            else -> {
                val level = arg.toIntOrNull()
                if (level != null) {
                    mapPhoneResult(phoneController.setVolume(level))
                } else {
                    ToolExecutionResult(ToolExecutionStatus.FAILED, "Invalid volume argument: $arg")
                }
            }
        }
    }

    private fun handleSetBrightness(command: PlannedAction): ToolExecutionResult {
        val arg = command.rawArguments?.lowercase() ?: return ToolExecutionResult(ToolExecutionStatus.FAILED, "Brightness level not specified")
        val currentBrightness = try {
            android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS
            ) * 100 / 255
        } catch (_: Exception) { 50 }

        return when (arg) {
            "up" -> {
                val newLevel = (currentBrightness + 10).coerceAtMost(100)
                mapPhoneResult(phoneController.setBrightness(newLevel))
            }
            "down" -> {
                val newLevel = (currentBrightness - 10).coerceAtLeast(0)
                mapPhoneResult(phoneController.setBrightness(newLevel))
            }
            else -> {
                val level = arg.toIntOrNull()
                if (level != null) {
                    mapPhoneResult(phoneController.setBrightness(level))
                } else {
                    ToolExecutionResult(ToolExecutionStatus.FAILED, "Invalid brightness argument: $arg")
                }
            }
        }
    }

    private fun handleToggleWifi(command: PlannedAction): ToolExecutionResult {
        val arg = command.rawArguments?.lowercase() ?: "toggle"
        return when (arg) {
            "on", "enable" -> mapPhoneResult(phoneController.toggleWifi(true))
            "off", "disable" -> mapPhoneResult(phoneController.toggleWifi(false))
            else -> mapPhoneResult(phoneController.toggleWifi(!phoneController.isWifiEnabled()))
        }
    }

    private fun handleToggleBluetooth(command: PlannedAction): ToolExecutionResult {
        val arg = command.rawArguments?.lowercase() ?: "toggle"
        return when (arg) {
            "on", "enable" -> mapPhoneResult(phoneController.toggleBluetooth(true))
            "off", "disable" -> mapPhoneResult(phoneController.toggleBluetooth(false))
            else -> mapPhoneResult(phoneController.toggleBluetooth(true))
        }
    }

    private fun handleToggleFlashlight(command: PlannedAction): ToolExecutionResult {
        val arg = command.rawArguments?.lowercase() ?: "toggle"
        return when (arg) {
            "on" -> mapPhoneResult(phoneController.toggleFlashlight(true))
            "off" -> mapPhoneResult(phoneController.toggleFlashlight(false))
            else -> mapPhoneResult(phoneController.toggleFlashlight(!phoneController.isFlashlightOn()))
        }
    }

    private fun handlePlayMedia(): ToolExecutionResult {
        return mapPhoneResult(phoneController.playMedia())
    }

    private fun handlePauseMedia(): ToolExecutionResult {
        return mapPhoneResult(phoneController.pauseMedia())
    }

    private fun handleNextTrack(): ToolExecutionResult {
        return mapPhoneResult(phoneController.nextTrack())
    }

    private fun handlePrevTrack(): ToolExecutionResult {
        return mapPhoneResult(phoneController.prevTrack())
    }

    private fun handleSetAlarm(command: PlannedAction): ToolExecutionResult {
        val arg = command.rawArguments ?: return ToolExecutionResult(ToolExecutionStatus.FAILED, "Alarm time not specified")
        val parts = arg.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (hour == null) return ToolExecutionResult(ToolExecutionStatus.FAILED, "Invalid alarm time: $arg")
        return mapPhoneResult(phoneController.setAlarm(hour, minute, command.messageOrQuery))
    }

    private fun handleSetTimer(command: PlannedAction): ToolExecutionResult {
        val arg = command.rawArguments ?: return ToolExecutionResult(ToolExecutionStatus.FAILED, "Timer duration not specified")
        val duration = arg.toIntOrNull()
            ?: return ToolExecutionResult(ToolExecutionStatus.FAILED, "Invalid timer duration: $arg")
        return mapPhoneResult(phoneController.setTimer(duration, command.messageOrQuery))
    }

    private fun handleScreenshot(): ToolExecutionResult {
        return mapPhoneResult(phoneController.takeScreenshot())
    }

    private fun handleOpenUrl(command: PlannedAction): ToolExecutionResult {
        val url = command.rawArguments ?: return ToolExecutionResult(ToolExecutionStatus.FAILED, "URL not specified")
        return mapPhoneResult(phoneController.openUrl(url))
    }

    private fun handleSearchWeb(command: PlannedAction): ToolExecutionResult {
        val query = command.rawArguments ?: command.messageOrQuery ?: return ToolExecutionResult(ToolExecutionStatus.FAILED, "Search query not specified")
        return mapPhoneResult(phoneController.searchWeb(query))
    }

    private fun handleTakePhoto(): ToolExecutionResult {
        return mapPhoneResult(phoneController.takePhoto())
    }

    private fun handleDoNotDisturb(enable: Boolean): ToolExecutionResult {
        return mapPhoneResult(phoneController.setDoNotDisturb(enable))
    }

    private fun mapPhoneResult(phoneResult: com.example.engine.phone.PhoneActionResult): ToolExecutionResult {
        val status = when (phoneResult.status) {
            com.example.engine.phone.PhoneActionStatus.SUCCESS -> ToolExecutionStatus.SUCCESS
            com.example.engine.phone.PhoneActionStatus.PERMISSION_REQUIRED -> ToolExecutionStatus.PERMISSION_REQUIRED
            com.example.engine.phone.PhoneActionStatus.NOT_AVAILABLE -> ToolExecutionStatus.NOT_INSTALLED
            com.example.engine.phone.PhoneActionStatus.UNSUPPORTED -> ToolExecutionStatus.UNSUPPORTED
            com.example.engine.phone.PhoneActionStatus.FAILED -> ToolExecutionStatus.FAILED
        }
        return ToolExecutionResult(status, phoneResult.message)
    }

    // --- Existing handlers below (unchanged) ---

    private suspend fun handleCheckProjectStatus(): ToolExecutionResult {
        val workspace = workspaceRegistry.getActiveWorkspace()
            ?: return ToolExecutionResult(
                ToolExecutionStatus.WORKSPACE_REQUIRED,
                "No active workspace configured. Please register a project path in Settings."
            )

        val validation = workspaceRegistry.validateWorkspace(workspace)
        if (!validation.isUsable) {
            return ToolExecutionResult(
                ToolExecutionStatus.WORKSPACE_REQUIRED,
                "Invalid workspace directory (${workspace.localPath}): ${validation.message}"
            )
        }

        val request = com.example.engine.termux.TermuxCommandRequest(
            executablePath = "/data/data/com.termux/files/usr/bin/git",
            arguments = listOf("status", "--short"),
            workingDirectory = workspace.localPath,
            description = "Check git project status",
            riskLevel = com.example.engine.termux.TermuxRiskLevel.READ_ONLY
        )

        val result = termuxWorker.executeCommand(request)
        return when (result.status) {
            com.example.engine.termux.TermuxExecutionStatus.SUCCESS -> {
                val output = result.stdout.trim()
                if (output.isBlank()) {
                    ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Project is clean. No changes detected.")
                } else {
                    ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Project changes:\n${truncatePreview(output)}")
                }
            }
            com.example.engine.termux.TermuxExecutionStatus.SETUP_REQUIRED -> {
                ToolExecutionResult(ToolExecutionStatus.SETUP_REQUIRED, result.message)
            }
            else -> ToolExecutionResult(ToolExecutionStatus.FAILED, result.message)
        }
    }

    private fun handleOpenSettings(): ToolExecutionResult {
        return try {
            val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Opened Android Settings")
        } catch (e: Exception) {
            ToolExecutionResult(ToolExecutionStatus.FAILED, "Failed to open Settings: ${e.message}")
        }
    }

    private fun handleOpenApp(command: PlannedAction): ToolExecutionResult {
        val target = command.targetAppOrPerson
            ?: return ToolExecutionResult(ToolExecutionStatus.FAILED, "No app target specified")

        val tool = toolRegistry.findTool(target)
        if (tool != null && tool.toolType == ToolType.WEB && tool.url != null) {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tool.url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Opened ${tool.name}")
            } catch (e: Exception) {
                ToolExecutionResult(ToolExecutionStatus.FAILED, "Failed to open ${tool.name}: ${e.message}")
            }
        }

        val packageName = tool?.installedPackageName ?: findInstalledPackage(target)
        if (packageName != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Launched $target")
            }
        }

        if (tool != null && !tool.installedOrAvailable) {
            return ToolExecutionResult(ToolExecutionStatus.NOT_INSTALLED, "${tool.name} is not installed")
        }

        return ToolExecutionResult(ToolExecutionStatus.FAILED, "Could not launch $target")
    }

    private fun findInstalledPackage(target: String): String? {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString()
            if (label.equals(target, ignoreCase = true)) {
                return info.activityInfo.packageName
            }
        }
        return null
    }

    private suspend fun handleCheckGithub(command: PlannedAction): ToolExecutionResult {
        val tool = toolRegistry.findTool("github")
        if (tool != null && tool.installedOrAvailable && tool.enabled) {
            val packageName = tool.installedPackageName ?: "com.github.android"
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Opened GitHub")
            }
        }
        return ToolExecutionResult(ToolExecutionStatus.NOT_INSTALLED, "GitHub app is not installed or enabled.")
    }

    private suspend fun handleTermuxCommand(command: PlannedAction): ToolExecutionResult {
        val cmd = command.rawArguments
            ?: return ToolExecutionResult(ToolExecutionStatus.FAILED, "No Termux command provided")

        val request = com.example.engine.termux.TermuxCommandRequest(
            executablePath = "/data/data/com.termux/files/usr/bin/sh",
            arguments = listOf("-c", cmd),
            description = "Execute: $cmd",
            riskLevel = command.riskLevel ?: com.example.engine.termux.TermuxRiskLevel.READ_ONLY
        )

        val result = termuxWorker.executeCommand(request)
        return when (result.status) {
            com.example.engine.termux.TermuxExecutionStatus.SUCCESS -> {
                val output = result.stdout.trim()
                if (output.isBlank()) {
                    ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Command completed successfully (no output)")
                } else {
                    ToolExecutionResult(ToolExecutionStatus.SUCCESS, truncatePreview(output))
                }
            }
            com.example.engine.termux.TermuxExecutionStatus.SETUP_REQUIRED -> {
                ToolExecutionResult(ToolExecutionStatus.SETUP_REQUIRED, result.message)
            }
            com.example.engine.termux.TermuxExecutionStatus.TIMED_OUT -> {
                ToolExecutionResult(ToolExecutionStatus.TIMED_OUT, result.message)
            }
            else -> ToolExecutionResult(ToolExecutionStatus.FAILED, result.message)
        }
    }

    private suspend fun handleDevelopmentAction(command: PlannedAction): ToolExecutionResult {
        val actionName = command.action.name
        val rawArgs = command.rawArguments ?: ""

        val (cmd, risk) = when (command.action) {
            CommandAction.BUILD -> {
                val buildCmd = if (rawArgs.isNotBlank()) rawArgs else "build"
                Pair(buildCmd, com.example.engine.termux.TermuxRiskLevel.MUTATING)
            }
            CommandAction.WORK_ON -> {
                Pair("work on ${command.rawArguments ?: ""}".trim(), com.example.engine.termux.TermuxRiskLevel.READ_ONLY)
            }
            CommandAction.PUSH -> {
                Pair("git push", com.example.engine.termux.TermuxRiskLevel.PUBLISHING)
            }
            CommandAction.DELETE -> {
                Pair("rm -rf ${command.rawArguments ?: ""}".trim(), com.example.engine.termux.TermuxRiskLevel.DESTRUCTIVE)
            }
            CommandAction.OVERWRITE -> {
                Pair("echo '${command.rawArguments ?: ""}' > file".trim(), com.example.engine.termux.TermuxRiskLevel.DESTRUCTIVE)
            }
            CommandAction.RUN_COMMAND -> {
                Pair(rawArgs, command.riskLevel ?: com.example.engine.termux.TermuxRiskLevel.READ_ONLY)
            }
            else -> Pair(rawArgs, com.example.engine.termux.TermuxRiskLevel.READ_ONLY)
        }

        val request = com.example.engine.termux.TermuxCommandRequest(
            executablePath = "/data/data/com.termux/files/usr/bin/sh",
            arguments = listOf("-c", cmd),
            description = "Execute $actionName: $cmd",
            riskLevel = risk
        )

        val result = termuxWorker.executeCommand(request)
        return when (result.status) {
            com.example.engine.termux.TermuxExecutionStatus.SUCCESS -> {
                val output = result.stdout.trim()
                if (output.isBlank()) {
                    ToolExecutionResult(ToolExecutionStatus.SUCCESS, "$actionName completed successfully")
                } else {
                    ToolExecutionResult(ToolExecutionStatus.SUCCESS, "$actionName output:\n${truncatePreview(output)}")
                }
            }
            com.example.engine.termux.TermuxExecutionStatus.SETUP_REQUIRED -> {
                ToolExecutionResult(ToolExecutionStatus.SETUP_REQUIRED, result.message)
            }
            com.example.engine.termux.TermuxExecutionStatus.TIMED_OUT -> {
                ToolExecutionResult(ToolExecutionStatus.TIMED_OUT, result.message)
            }
            else -> ToolExecutionResult(ToolExecutionStatus.FAILED, result.message)
        }
    }

    private suspend fun handleCommunication(
        command: PlannedAction,
        providedResolution: ContactResolutionResult?
    ): ToolExecutionResult {
        val resolution = providedResolution ?: contactResolver.resolveCommandTarget(command)

        return when (resolution) {
            is ContactResolutionResult.Resolved -> {
                val destValue = resolution.destination.value
                val maskedDest = if (command.action == CommandAction.EMAIL) destValue else PrivacyUtils.maskPhoneNumber(destValue)

                when (command.action) {
                    CommandAction.CALL -> {
                        val intent = Intent(Intent.ACTION_DIAL).apply {
                            data = Uri.parse("tel:$destValue")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                            ToolExecutionResult(
                                ToolExecutionStatus.SUCCESS,
                                "Opened dialer for ${resolution.displayName} ($maskedDest)"
                            )
                        } catch (e: Exception) {
                            ToolExecutionResult(ToolExecutionStatus.FAILED, "No dialer application found.")
                        }
                    }

                    CommandAction.TEXT -> {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("smsto:$destValue")
                            putExtra("sms_body", resolution.message ?: command.messageOrQuery ?: "")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                            ToolExecutionResult(
                                ToolExecutionStatus.SUCCESS,
                                "Opened SMS for ${resolution.displayName} ($maskedDest)"
                            )
                        } catch (e: Exception) {
                            ToolExecutionResult(ToolExecutionStatus.FAILED, "No SMS application found.")
                        }
                    }

                    CommandAction.EMAIL -> {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:$destValue")
                            putExtra(Intent.EXTRA_TEXT, resolution.message ?: command.messageOrQuery ?: "")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                            ToolExecutionResult(
                                ToolExecutionStatus.SUCCESS,
                                "Opened email client for ${resolution.displayName}"
                            )
                        } catch (e: Exception) {
                            ToolExecutionResult(ToolExecutionStatus.FAILED, "No email application found.")
                        }
                    }

                    else -> ToolExecutionResult(ToolExecutionStatus.FAILED, "Invalid communication action")
                }
            }

            is ContactResolutionResult.ProviderError -> {
                ToolExecutionResult(
                    ToolExecutionStatus.FAILED,
                    resolution.message
                )
            }

            ContactResolutionResult.PermissionRequired -> {
                ToolExecutionResult(
                    ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED,
                    "Contacts permission required to resolve contact."
                )
            }

            ContactResolutionResult.NotFound -> {
                ToolExecutionResult(
                    ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED,
                    "Contact not found."
                )
            }

            is ContactResolutionResult.Ambiguous -> {
                ToolExecutionResult(
                    ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED,
                    "Multiple contact candidates found. Selection required."
                )
            }

            is ContactResolutionResult.MultipleDestinations -> {
                ToolExecutionResult(
                    ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED,
                    "Multiple destinations found. Selection required."
                )
            }

            ContactResolutionResult.ResolutionRequired -> {
                ToolExecutionResult(
                    ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED,
                    "Contact name or details missing."
                )
            }
        }
    }
}
