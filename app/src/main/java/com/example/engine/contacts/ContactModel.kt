package com.example.engine.contacts

data class ContactDestination(
    val value: String,
    val label: String
)

data class ContactCandidate(
    val contactId: String,
    val displayName: String,
    val destinations: List<ContactDestination>
)

sealed class ContactResolutionResult {
    data class Resolved(
        val displayName: String,
        val destination: ContactDestination,
        val message: String? = null
    ) : ContactResolutionResult()

    data class Ambiguous(
        val query: String,
        val candidates: List<ContactCandidate>,
        val message: String? = null
    ) : ContactResolutionResult()

    data class MultipleDestinations(
        val displayName: String,
        val destinations: List<ContactDestination>,
        val message: String? = null
    ) : ContactResolutionResult()

    data class ProviderError(
        val message: String = "Unable to access contacts on device."
    ) : ContactResolutionResult()

    object NotFound : ContactResolutionResult()
    object PermissionRequired : ContactResolutionResult()
    object ResolutionRequired : ContactResolutionResult()
}

class ContactsProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
