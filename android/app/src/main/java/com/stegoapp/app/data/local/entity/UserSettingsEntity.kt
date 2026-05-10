package com.stegoapp.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单行表,保存当前登录用户的 E2EE 助记词与派生结果。
 * 主键固定为 0,应用只允许写入一行。phrase 是原文,
 * userKeyHex/fingerprintHex 为派生缓存(派生耗时 ~100-300ms PBKDF2)。
 * 不持久化 mkey(见 spec §2 非目标)。
 */
@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = 0,
    val phrase: String,
    val userKeyHex: String,
    val fingerprintHex: String,
)
