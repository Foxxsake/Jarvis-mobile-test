package com.example.engine.policy

import com.example.data.AccessPolicy
import com.example.engine.CommandAction
import com.example.engine.PlannedAction
import com.example.engine.ToolRegistry
import com.example.engine.termux.TermuxCommandClassifier
import com.example.engine.termux.TermuxRiskLevel

sealed class PolicyDecision {
    data object Allowed : PolicyDecision()
    data class RequiresApproval(val reason: String, val promptDetails: String) : PolicyDecision()
    data class Blocked(val reason: String) : PolicyDecision()
}

/**
 * The final execution safety and policy guard.
 * Evaluated immediately before any action is executed by the system.
 * App-level policies, tool registry policies, and core safety invariants
 * MUST be enforced here regardless of source (typed, voice, AI, script).
 */
object ExecutionPolicyGuard {

    fun evaluate(
        action: PlannedAction,
        toolRegistry: ToolRegistry,
        isApprovedByUser: Boolean
    ): PolicyDecision {
        // 1. HARD INVARIANT: Never spend money / financial transactions
        val textToCheck = "${action.action} ${action.rawArguments ?: ""} ${action.targetAppOrPerson ?: ""}".lowercase()
        if (textToCheck.contains("buy ") || textToCheck.contains("purchase ") || textToCheck.contains("pay ") || textToCheck.contains("subscribe") || textToCheck.contains("pay $")) {
            return PolicyDecision.Blocked("Automatic financial transactions and money spending are permanently prohibited by safety policy.")
        }

        // 2. CHECK TOOL-LEVEL ACCESS POLICY FROM REGISTRY
        val targetTool = action.targetAppOrPerson?.let { toolRegistry.findTool(it) }
            ?: if (action.action == CommandAction.TERMUX_COMMAND || action.action == CommandAction.BUILD || action.action == CommandAction.PUSH) toolRegistry.findTool("termux") else null

        if (targetTool != null) {
            if (!targetTool.enabled) {
                return PolicyDecision.Blocked("Tool '${targetTool.name}' is disabled in settings.")
            }
            when (targetTool.policy) {
                AccessPolicy.BLOCK -> {
                    return PolicyDecision.Blocked("Tool '${targetTool.name}' is blocked by policy.")
                }
                AccessPolicy.ASK_EACH_TIME -> {
                    if (!isApprovedByUser) {
                        return PolicyDecision.RequiresApproval(
                            reason = "Access policy for '${targetTool.name}' requires confirmation.",
                            promptDetails = "Open ${targetTool.name}"
                        )
                    }
                }
                AccessPolicy.ALLOW -> {
                    // Allowed to continue to consequential action invariant checks
                }
            }
        }

        // 3. HARD INVARIANT: Consequential Communication Actions ALWAYS require explicit user approval
        when (action.action) {
            CommandAction.CALL -> {
                if (!isApprovedByUser) {
                    val target = action.targetAppOrPerson ?: "unknown recipient"
                    return PolicyDecision.RequiresApproval(
                        reason = "Making a phone call is a consequential external communication action.",
                        promptDetails = "Call $target"
                    )
                }
            }
            CommandAction.TEXT -> {
                if (!isApprovedByUser) {
                    val target = action.targetAppOrPerson ?: "unknown recipient"
                    val msg = action.messageOrQuery ?: ""
                    return PolicyDecision.RequiresApproval(
                        reason = "Sending an SMS text is a consequential external communication action.",
                        promptDetails = "Send text to $target: \"$msg\""
                    )
                }
            }
            CommandAction.EMAIL -> {
                if (!isApprovedByUser) {
                    val target = action.targetAppOrPerson ?: "unknown recipient"
                    val msg = action.messageOrQuery ?: ""
                    return PolicyDecision.RequiresApproval(
                        reason = "Sending an email is a consequential external communication action.",
                        promptDetails = "Send email to $target: \"$msg\""
                    )
                }
            }
            CommandAction.PUSH -> {
                if (!isApprovedByUser) {
                    return PolicyDecision.RequiresApproval(
                        reason = "Publishing code to a remote repository is a consequential action.",
                        promptDetails = "Git push to remote repository"
                    )
                }
            }
            CommandAction.TERMUX_COMMAND -> {
                val cmd = action.rawArguments ?: ""
                val actualRisk = TermuxCommandClassifier.classifyCommandLine(cmd)
                if (actualRisk != TermuxRiskLevel.READ_ONLY && !isApprovedByUser) {
                    return PolicyDecision.RequiresApproval(
                        reason = "Execution risk level is $actualRisk.",
                        promptDetails = "Run command: $cmd"
                    )
                }
            }
            // Phone control actions are read-only device controls - always allowed
            CommandAction.SET_VOLUME,
            CommandAction.SET_BRIGHTNESS,
            CommandAction.TOGGLE_WIFI,
            CommandAction.TOGGLE_BLUETOOTH,
            CommandAction.TOGGLE_FLASHLIGHT,
            CommandAction.PLAY_MEDIA,
            CommandAction.PAUSE_MEDIA,
            CommandAction.NEXT_TRACK,
            CommandAction.PREV_TRACK,
            CommandAction.SET_ALARM,
            CommandAction.SET_TIMER,
            CommandAction.SCREENSHOT,
            CommandAction.OPEN_URL,
            CommandAction.SEARCH_WEB,
            CommandAction.TAKE_PHOTO,
            CommandAction.DO_NOT_DISTURB_ON,
            CommandAction.DO_NOT_DISTURB_OFF -> {
                // Always allowed - read-only device controls
            }
            else -> {
                // Unimplemented placeholders (DELETE, OVERWRITE, BUILD, WORK_ON, RUN_COMMAND)
                // or non-consequential read-only actions pass through to execution handler
            }
        }

        return PolicyDecision.Allowed
    }
}
