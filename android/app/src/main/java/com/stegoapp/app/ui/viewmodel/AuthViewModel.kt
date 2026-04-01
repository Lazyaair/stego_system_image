package com.stegoapp.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegoapp.app.api.ApiClient
import com.stegoapp.app.api.AuthRequest
import com.stegoapp.app.data.local.AppDatabase
import com.stegoapp.app.data.local.TokenStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val tokenStore = TokenStore(app)

    val isAuthenticated: StateFlow<Boolean> = tokenStore.token
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val userId: StateFlow<String?> = tokenStore.userId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val username: StateFlow<String?> = tokenStore.username
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun login(username: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val res = ApiClient.authApi.login(AuthRequest(username, password))
                tokenStore.save(res.token, res.user_id, res.username)
                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message ?: "Login failed"
            } finally {
                _loading.value = false
            }
        }
    }

    fun register(username: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val res = ApiClient.authApi.register(AuthRequest(username, password))
                tokenStore.save(res.token, res.user_id, res.username)
                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message ?: "Registration failed"
            } finally {
                _loading.value = false
            }
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            tokenStore.clear()
            val db = AppDatabase.getInstance(getApplication())
            db.contactDao().deleteAll()
            db.messageDao().deleteAll()
            db.blacklistDao().deleteAll()
            onDone()
        }
    }

    /** Called when kicked by another device — clear token but keep local data */
    fun onKicked(onDone: () -> Unit) {
        viewModelScope.launch {
            tokenStore.clear()
            onDone()
        }
    }

    fun clearError() { _error.value = null }
}
