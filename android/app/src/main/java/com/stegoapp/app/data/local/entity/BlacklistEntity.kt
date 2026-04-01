package com.stegoapp.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blacklist")
data class BlacklistEntity(
    @PrimaryKey val userId: String,
    val username: String,
    val blockedAt: String = ""
)
