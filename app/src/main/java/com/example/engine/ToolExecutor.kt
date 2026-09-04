package com.example.engine

import android.content.Context
import android.content.Intent
import android.net.Uri

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
    private val contactResolver: ContactResolver = ContactResolver()
) {
    fun executeAction(command: ParsedCommand, isLocalProcessingEnabled: Boolean = true): ToolExecutionResult {
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
            CommandAction.EMAIL -> handleCommunication(command)
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

    private fun handleOpenApp(command: ParsedCommand): ToolExecutionResult {
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

        if (tool.toolType == ToolType.WEB && tool.url != null) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tool.url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Opened ${tool.name} in browser")
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
                        ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Launched ${tool.name}")
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

    private fun handleCheckGithub(command: ParsedCommand): ToolExecutionResult {
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

    private fun handleCommunication(command: ParsedCommand): ToolExecutionResult {
        val target = command.targetAppOrPerson
        val resolution = contactResolver.resolveContact(target)

        when (resolution) {
            is ContactResolutionResult.Resolved -> {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (command.action == CommandAction.EMAIL) {
                    intent.data = Uri.parse("mailto:${resolution.destination}")
                    intent.putExtra(Intent.EXTRA_TEXT, command.messageOrQuery ?: "")
                } else if (command.action == CommandAction.TEXT) {
                    intent.data = Uri.parse("smsto:${resolution.destination}")
                    intent.putExtra("sms_body", command.messageOrQuery ?: "")
                } else if (command.action == CommandAction.CALL) {
                    intent.action = Intent.ACTION_DIAL
                    intent.data = Uri.parse("tel:${resolution.destination}")
                }
                return try {
                    context.startActivity(intent)
                    ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Opened communication for ${resolution.name}")
                } catch (e: Exception) {
                    ToolExecutionResult(ToolExecutionStatus.FAILED, "No intent handler available for ${command.action.name.lowercase()}")
                }
            }
            else -> {
                return ToolExecutionResult(
                    ToolExecutionStatus.CONTACT_RESOLUTION_REQUIRED,
                    "Contact resolution required for target: '${target ?: command.rawArguments ?: "unknown"}'"
                )
            }
        }
    }
}
