package com.stegoapp.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stegoapp.app.crypto.CryptoUtils
import com.stegoapp.app.data.local.AppDatabase
import com.stegoapp.app.data.local.entity.UserSettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 负责当前用户的 E2EE 助记词(self phrase)。派生 user_key 使用 PBKDF2 100k 迭代,
 * 必须在 IO 线程执行,永远不能在主线程调用 deriveUserKey。
 */
class ProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getInstance(app).userSettingsDao()

    private val settingsFlow: StateFlow<UserSettingsEntity?> = dao.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selfPhrase: StateFlow<String> = settingsFlow
        .map { it?.phrase.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val fingerprintHex: StateFlow<String> = settingsFlow
        .map { it?.fingerprintHex.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val isConfigured: StateFlow<Boolean> = settingsFlow
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun saveSelfPhrase(phrase: String) {
        val trimmed = phrase.trim()
        if (trimmed.isEmpty()) {
            _error.value = "助记词不能为空"
            return
        }
        viewModelScope.launch {
            _saving.value = true
            _error.value = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val userKey = CryptoUtils.deriveUserKey(trimmed)
                    val fp = CryptoUtils.fingerprint(userKey)
                    UserSettingsEntity(
                        id = 0,
                        phrase = trimmed,
                        userKeyHex = userKey.toHexLower(),
                        fingerprintHex = fp.toHexLower(),
                    )
                }
                dao.upsert(result)
            } catch (e: Exception) {
                _error.value = e.message ?: "派生密钥失败"
            } finally {
                _saving.value = false
            }
        }
    }

    fun clear() {
        viewModelScope.launch { dao.clear() }
    }

    fun clearError() { _error.value = null }

    private fun ByteArray.toHexLower(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
