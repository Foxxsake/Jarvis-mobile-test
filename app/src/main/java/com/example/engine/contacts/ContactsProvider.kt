package com.example.engine.contacts

interface ContactsProvider {
    fun hasPermission(): Boolean
    suspend fun searchContacts(query: String, isEmail: Boolean = false): List<ContactCandidate>
    suspend fun getAllContacts(isEmail: Boolean = false): List<ContactCandidate>
}
