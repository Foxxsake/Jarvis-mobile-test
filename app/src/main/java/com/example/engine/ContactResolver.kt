package com.example.engine

import com.example.engine.contacts.ContactCandidate
import com.example.engine.contacts.ContactResolutionResult
import com.example.engine.contacts.ContactsProvider

class ContactResolver(private val contactsProvider: ContactsProvider) {

    suspend fun resolveCommandTarget(command: PlannedAction): ContactResolutionResult {
        if (!contactsProvider.hasPermission()) {
            return ContactResolutionResult.PermissionRequired
        }

        val isEmail = command.action == CommandAction.EMAIL

        try {
            if (command.action == CommandAction.CALL) {
                val target = command.targetAppOrPerson?.trim() ?: command.rawArguments?.trim()
                if (target.isNullOrBlank()) {
                    return ContactResolutionResult.ResolutionRequired
                }
                return resolveNameTarget(target, isEmail = false, explicitMessage = null)
            }

            if (command.action == CommandAction.TEXT || command.action == CommandAction.EMAIL) {
                val explicitTarget = command.targetAppOrPerson?.trim()
                val explicitMessage = command.messageOrQuery?.trim()

                if (!explicitTarget.isNullOrBlank()) {
                    return resolveNameTarget(explicitTarget, isEmail = isEmail, explicitMessage = explicitMessage)
                }

                val rawInput = command.rawArguments?.trim()
                if (rawInput.isNullOrBlank()) {
                    return ContactResolutionResult.ResolutionRequired
                }

                val allContacts = contactsProvider.getAllContacts(isEmail = isEmail)
                val lowerInput = rawInput.lowercase()

                val prefixMatches = allContacts.filter { contact ->
                    val lowerName = contact.displayName.trim().lowercase()
                    lowerName.isNotEmpty() && (lowerInput == lowerName || lowerInput.startsWith("$lowerName "))
                }

                if (prefixMatches.isNotEmpty()) {
                    val sorted = prefixMatches.sortedByDescending { it.displayName.trim().length }
                    val longestLength = sorted.first().displayName.trim().length
                    val topTier = sorted.filter { it.displayName.trim().length == longestLength }

                    if (topTier.size == 1) {
                        val matched = topTier.first()
                        val matchedName = matched.displayName.trim()
                        val remainingMessage = if (rawInput.length > matchedName.length) {
                            rawInput.substring(matchedName.length).trim()
                        } else {
                            null
                        }
                        val finalMsg = if (!explicitMessage.isNullOrBlank()) explicitMessage else remainingMessage
                        return resolveCandidateDestinations(matched, finalMsg)
                    } else {
                        return ContactResolutionResult.Ambiguous(
                            query = rawInput,
                            candidates = topTier,
                            message = explicitMessage
                        )
                    }
                }

                return resolveNameTarget(rawInput, isEmail = isEmail, explicitMessage = explicitMessage)
            }
        } catch (e: Exception) {
            return ContactResolutionResult.ProviderError("Contacts provider error: ${e.message ?: "Unknown error"}")
        }

        return ContactResolutionResult.ResolutionRequired
    }

    private suspend fun resolveNameTarget(targetName: String, isEmail: Boolean, explicitMessage: String?): ContactResolutionResult {
        val candidates = contactsProvider.searchContacts(targetName, isEmail = isEmail)
        if (candidates.isEmpty()) {
            return ContactResolutionResult.NotFound
        }

        val exactMatches = candidates.filter { it.displayName.equals(targetName, ignoreCase = true) }
        val pool = if (exactMatches.isNotEmpty()) exactMatches else candidates

        if (pool.size == 1) {
            return resolveCandidateDestinations(pool.first(), explicitMessage)
        }

        return ContactResolutionResult.Ambiguous(
            query = targetName,
            candidates = pool,
            message = explicitMessage
        )
    }

    fun resolveCandidateDestinations(
        candidate: ContactCandidate,
        message: String?
    ): ContactResolutionResult {
        val dests = candidate.destinations
        if (dests.isEmpty()) {
            return ContactResolutionResult.NotFound
        }
        if (dests.size == 1) {
            return ContactResolutionResult.Resolved(
                displayName = candidate.displayName,
                destination = dests.first(),
                message = message
            )
        }
        return ContactResolutionResult.MultipleDestinations(
            displayName = candidate.displayName,
            destinations = dests,
            message = message
        )
    }
}
