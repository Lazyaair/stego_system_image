package com.stegoapp.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.data.local.AppDatabase
import com.stegoapp.app.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ContactViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val contactDao = db.contactDao()

    val contacts: StateFlow<List<ContactEntity>> = contactDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun addContactByCode(code: String, onSuccess: (ContactEntity) -> Unit) {
        viewModelScope.launch {
            try {
                val info = ApiClient.inviteApi.lookupCode(code)
                val existing = contactDao.getById(info.user_id)
                if (existing != null) {
                    onSuccess(existing)
                    return@launch
                }
                val contact = ContactEntity(
                    userId = info.user_id,
                    username = info.username,
                    status = "accepted",
                    addedAt = System.currentTimeMillis().toString()
                )
                contactDao.insert(contact)
                onSuccess(contact)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to add contact"
            }
        }
    }

    fun acceptContact(userId: String, username: String) {
        viewModelScope.launch {
            contactDao.insert(ContactEntity(
                userId = userId,
                username = username,
                status = "accepted",
                addedAt = System.currentTimeMillis().toString()
            ))
        }
    }

    fun removeContact(userId: String) {
        viewModelScope.launch { contactDao.deleteById(userId) }
    }

    fun clearError() { _error.value = null }
}
