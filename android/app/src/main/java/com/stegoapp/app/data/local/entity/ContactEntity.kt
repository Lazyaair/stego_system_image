package com.stegoapp.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val userId: String,
    val username: String,
    val nickname: String = "",
    val status: String = "accepted",
    val addedAt: String = ""
)
