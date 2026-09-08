package com.example.engine.policy

/**
 * Explicit execution authorization context passed to ToolExecutor.
 * Untrusted callers (AI models, skills, external calls) default to [untrusted],
 * which forces ExecutionPolicyGuard to enforce ALLOW/ASK_EACH_TIME/BLOCK policies
 * and consequential action approval.
 *
 * Only JarvisRuntime can construct [userApproved] after verifying user consent or policy compliance.
 */
data class ExecutionAuthorization private constructor(
    val isApprovedByUser: Boolean
) {
    companion object {
        private val UNTRUSTED = ExecutionAuthorization(isApprovedByUser = false)
        private val USER_APPROVED = ExecutionAuthorization(isApprovedByUser = true)

        fun untrusted(): ExecutionAuthorization = UNTRUSTED
        fun userApproved(): ExecutionAuthorization = USER_APPROVED
    }
}
