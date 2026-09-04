package com.example.engine

sealed class ContactResolutionResult {
    data class Resolved(val name: String, val destination: String) : ContactResolutionResult()
    object NotFound : ContactResolutionResult()
    object PermissionRequired : ContactResolutionResult()
    object ResolutionRequired : ContactResolutionResult()
    object Ambiguous : ContactResolutionResult()
}

class ContactResolver {
    fun resolveContact(target: String?): ContactResolutionResult {
        if (target.isNullOrBlank()) {
            return ContactResolutionResult.ResolutionRequired
        }
        // Unresolved communication commands return ResolutionRequired for now
        return ContactResolutionResult.ResolutionRequired
    }
}
