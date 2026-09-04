package com.example.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.engine.contacts.ContactResolutionResult
import com.example.util.PrivacyUtils

enum class ToolExecutionStatus {
    SUCCESS,
    NOT_INSTALLED,
    UNSUPPORTED,
    NOT_IMPLEMENTED,
    CONTACT_RESOLUTION_REQUIRED,
    REQUIRES_CONNECTION,
    FAILED
}

data class ToolExecutionResult(
    val status: ToolExecutionStatus,
    val message: String
)

class ToolExecutor(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val contactResolver: ContactResolver
) {
    suspend fun executeAction(
        command: PlannedAction,
        resolvedResult: ContactResolutionResult? = null,
        isLocalProcessingEnabled: Boolean = true
    ): ToolExecutionResult {
        if (!isLocalProcessingEnabled) {
            return ToolExecutionResult(
                ToolExecutionStatus.FAILED,
                "Local command processing is currently disabled in settings."
            )
        }

        return when (command.action) {
            CommandAction.OPEN_SETTINGS -> handleOpenSettings()
            CommandAction.OPEN_APP -> handleOpenApp(command)
            CommandAction.CALL,
            CommandAction.TEXT,
            CommandAction.EMAIL -> handleCommunication(command, resolvedResult)
            CommandAction.CHECK_GITHUB -> handleCheckGithub(command)
            CommandAction.BUILD,
            CommandAction.WORK_ON,
            CommandAction.PUSH,
            CommandAction.DELETE,
            CommandAction.OVERWRITE,
            CommandAction.RUN_COMMAND -> ToolExecutionResult(
                ToolExecutionStatus.NOT_IMPLEMENTED,
                "Development workflow action '${command.action.name}' is a placeholder and not yet implemented."
            )
            CommandAction.UNKNOWN -> ToolExecutionResult(
                ToolExecutionStatus.NOT_IMPLEMENTED,
                "Command not recognized locally. Requires AI engine."
            )
        }
    }

    private fun handleOpenSettings(): ToolExecutionResult {
        val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Opened Android Settings")
        } catch (e: Exception) {
            ToolExecutionResult(ToolExecutionStatus.FAILED, "Failed to open settings: ${e.message}")
        }
    }

    private fun handleOpenApp(command: PlannedAction): ToolExecutionResult {
        val targetName = command.targetAppOrPerson ?: return ToolExecutionResult(ToolExecutionStatus.FAILED, "No target tool or app specified.")

        if (targetName.lowercase() == "settings") {
            return handleOpenSettings()
        }

        val tool = toolRegistry.findTool(targetName)
            ?: return ToolExecutionResult(ToolExecutionStatus.NOT_INSTALLED, "Tool or app '$targetName' is not registered or installed.")

        if (!tool.enabled) {
            return ToolExecutionResult(ToolExecutionStatus.FAILED, "Tool '${tool.name}' is disabled in settings.")
        }

        if (!tool.installedOrAvailable) {
            return ToolExecutionResult(ToolExecutionStatus.NOT_INSTALLED, "${tool.name} is not installed.")
        }

        val followUpText = command.followUp
        val followUpSuffix = if (!followUpText.isNullOrBlank()) {
            " Follow-up automation '$followUpText' is not implemented yet."
        } else {
            ""
        }

        val appDisplayName = if (tool.id == "pydroid") "Pydroid" else tool.name

        if (tool.toolType == ToolType.WEB && tool.url != null) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tool.url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                val baseMsg = if (followUpSuffix.isNotBlank()) "$appDisplayName opened." else "Opened ${tool.name} in browser"
                ToolExecutionResult(ToolExecutionStatus.SUCCESS, baseMsg + followUpSuffix)
            } catch (e: Exception) {
                ToolExecutionResult(ToolExecutionStatus.FAILED, "Failed to open browser: ${e.message}")
            }
        }

        if (tool.toolType == ToolType.APP) {
            val pkg = tool.installedPackageName ?: tool.packageNames.firstOrNull()
            if (pkg != null) {
                val intent = try {
                    context.packageManager?.getLaunchIntentForPackage(pkg)
                } catch (e: Exception) {
                    null
                }
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    return try {
                        context.startActivity(intent)
                        val baseMsg = if (followUpSuffix.isNotBlank()) "$appDisplayName opened." else "Launched ${tool.name}"
                        ToolExecutionResult(ToolExecutionStatus.SUCCESS, baseMsg + followUpSuffix)
                    } catch (e: Exception) {
                        ToolExecutionResult(ToolExecutionStatus.FAILED, "Failed to launch ${tool.name}: ${e.message}")
                    }
                } else {
                    return ToolExecutionResult(ToolExecutionStatus.NOT_INSTALLED, "Could not find launch intent for ${tool.name} ($pkg)")
                }
            } else {
                return ToolExecutionResult(ToolExecutionStatus.NOT_INSTALLED, "${tool.name} is not installed.")
            }
        }

        return ToolExecutionResult(ToolExecutionStatus.FAILED, "Invalid tool configuration")
    }

    private fun handleCheckGithub(command: PlannedAction): ToolExecutionResult {
        val githubTool = toolRegistry.findTool("github")
        if (githubTool != null && githubTool.enabled && githubTool.installedOrAvailable) {
            val pkg = githubTool.installedPackageName ?: githubTool.packageNames.firstOrNull()
            if (pkg != null) {
                val intent = context.packageManager?.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    return try {
                        context.startActivity(intent)
                        ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Opened GitHub app")
                    } catch (e: Exception) {
                        ToolExecutionResult(ToolExecutionStatus.FAILED, "Failed to launch GitHub app: ${e.message}")
                    }
                }
            }
        }
        return ToolExecutionResult(ToolExecutionStatus.NOT_INSTALLED, "GitHub app is not installed or enabled.")
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
