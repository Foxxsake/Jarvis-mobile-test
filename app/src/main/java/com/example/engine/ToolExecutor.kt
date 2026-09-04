package com.example.engine

import android.content.Context
import android.content.Intent
import android.net.Uri

enum class ToolExecutionStatus {
    SUCCESS,
    NOT_INSTALLED,
    UNSUPPORTED,
    FAILED,
    REQUIRES_CONNECTION
}

data class ToolExecutionResult(
    val status: ToolExecutionStatus,
    val message: String
)

class ToolExecutor(private val context: Context, private val toolRegistry: ToolRegistry) {
    fun executeAction(command: ParsedCommand): ToolExecutionResult {
        return when (command.category) {
            CommandCategory.DEVICE_ACTION -> handleDeviceAction(command)
            CommandCategory.COMMUNICATION -> handleCommunication(command)
            CommandCategory.DEVELOPMENT -> handleDevelopment(command)
            else -> ToolExecutionResult(ToolExecutionStatus.UNSUPPORTED, "Command type not supported for execution.")
        }
    }

    private fun handleDeviceAction(command: ParsedCommand): ToolExecutionResult {
        val targetName = command.targetAppOrPerson?.lowercase() ?: return ToolExecutionResult(ToolExecutionStatus.FAILED, "No target specified.")
        
        if (targetName == "settings") {
            val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Opened Settings")
            } catch (e: Exception) {
                ToolExecutionResult(ToolExecutionStatus.FAILED, "Failed to open settings: ${e.message}")
            }
        }

        val tool = toolRegistry.tools.value.find { it.name.lowercase() == targetName } 
            ?: return ToolExecutionResult(ToolExecutionStatus.UNSUPPORTED, "Unknown app or tool: $targetName")

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

        if (tool.toolType == ToolType.APP && tool.packageName != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(tool.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    context.startActivity(intent)
                    ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Launched ${tool.name}")
                } catch (e: Exception) {
                    ToolExecutionResult(ToolExecutionStatus.FAILED, "Failed to launch ${tool.name}: ${e.message}")
                }
            } else {
                 return ToolExecutionResult(ToolExecutionStatus.FAILED, "Could not create launch intent for ${tool.name}")
            }
        }

        return ToolExecutionResult(ToolExecutionStatus.FAILED, "Invalid tool configuration")
    }

    private fun handleCommunication(command: ParsedCommand): ToolExecutionResult {
        val intent = Intent(Intent.ACTION_SENDTO)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val text = command.rawText.lowercase()
        if (text.startsWith("email")) {
            intent.data = Uri.parse("mailto:")
            val subject = "Message for ${command.targetAppOrPerson}"
            intent.putExtra(Intent.EXTRA_SUBJECT, subject)
            intent.putExtra(Intent.EXTRA_TEXT, command.messageOrQuery)
        } else if (text.startsWith("text")) {
            intent.data = Uri.parse("smsto:")
            intent.putExtra("sms_body", command.messageOrQuery)
        } else if (text.startsWith("call")) {
            intent.action = Intent.ACTION_DIAL
            intent.data = Uri.parse("tel:")
        } else {
             return ToolExecutionResult(ToolExecutionStatus.UNSUPPORTED, "Unsupported communication action")
        }
        
        return try {
            context.startActivity(intent)
            ToolExecutionResult(ToolExecutionStatus.SUCCESS, "Opened communication app")
        } catch (e: Exception) {
            ToolExecutionResult(ToolExecutionStatus.FAILED, "No app available to handle this request.")
        }
    }

    private fun handleDevelopment(command: ParsedCommand): ToolExecutionResult {
        return ToolExecutionResult(ToolExecutionStatus.FAILED, "PLACEHOLDER / NOT_IMPLEMENTED")
    }
}
