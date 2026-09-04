package com.example.engine.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

class AndroidContactsProvider(private val context: Context) : ContactsProvider {

    override fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun searchContacts(query: String, isEmail: Boolean): List<ContactCandidate> {
        if (!hasPermission()) return emptyList()
        val all = getAllContacts(isEmail)
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return all

        return all.filter { candidate ->
            candidate.displayName.lowercase().contains(cleanQuery)
        }
    }

    override fun getAllContacts(isEmail: Boolean): List<ContactCandidate> {
        if (!hasPermission()) return emptyList()

        val resultMap = LinkedHashMap<String, MutableContactCandidate>()
        val contentResolver = context.contentResolver

        if (!isEmail) {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
            )

            try {
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                    val labelIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)

                    while (cursor.moveToNext()) {
                        val id = if (idIdx >= 0) cursor.getString(idIdx) ?: "" else ""
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "Unknown" else "Unknown"
                        val number = if (numberIdx >= 0) cursor.getString(numberIdx) ?: "" else ""
                        val type = if (typeIdx >= 0) cursor.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                        val customLabel = if (labelIdx >= 0) cursor.getString(labelIdx) else null

                        val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.resources,
                            type,
                            customLabel ?: ""
                        ).toString()

                        if (id.isNotBlank() && number.isNotBlank()) {
                            val candidate = resultMap.getOrPut(id) {
                                MutableContactCandidate(id, name, mutableListOf())
                            }
                            if (candidate.destinations.none { it.value == number }) {
                                candidate.destinations.add(ContactDestination(value = number, label = label))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        } else {
            val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.DATA,
                ContactsContract.CommonDataKinds.Email.TYPE,
                ContactsContract.CommonDataKinds.Email.LABEL
            )

            try {
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                    val emailIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA)
                    val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.TYPE)
                    val labelIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.LABEL)

                    while (cursor.moveToNext()) {
                        val id = if (idIdx >= 0) cursor.getString(idIdx) ?: "" else ""
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "Unknown" else "Unknown"
                        val email = if (emailIdx >= 0) cursor.getString(emailIdx) ?: "" else ""
                        val type = if (typeIdx >= 0) cursor.getInt(typeIdx) else ContactsContract.CommonDataKinds.Email.TYPE_OTHER
                        val customLabel = if (labelIdx >= 0) cursor.getString(labelIdx) else null

                        val label = ContactsContract.CommonDataKinds.Email.getTypeLabel(
                            context.resources,
                            type,
                            customLabel ?: ""
                        ).toString()

                        if (id.isNotBlank() && email.isNotBlank()) {
                            val candidate = resultMap.getOrPut(id) {
                                MutableContactCandidate(id, name, mutableListOf())
                            }
                            if (candidate.destinations.none { it.value == email }) {
                                candidate.destinations.add(ContactDestination(value = email, label = label))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return resultMap.values.map { it.toContactCandidate() }
    }

    private data class MutableContactCandidate(
        val contactId: String,
        val displayName: String,
        val destinations: MutableList<ContactDestination>
    ) {
        fun toContactCandidate() = ContactCandidate(contactId, displayName, destinations)
    }
}
