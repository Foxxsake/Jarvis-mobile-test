package com.example.engine.contacts

interface ContactsProvider {
    fun hasPermission(): Boolean
    fun searchContacts(query: String, isEmail: Boolean = false): List<ContactCandidate>
    fun getAllContacts(isEmail: Boolean = false): List<ContactCandidate>
}
