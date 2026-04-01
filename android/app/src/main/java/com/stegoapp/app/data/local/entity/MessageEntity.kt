package com.stegoapp.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val direction: String,
    val content: String,
    val contentType: String = "text",
    val stegoImage: String? = null,
    val status: String = "sending",
    val burnAfter: Int = 0,
    val burned: Boolean = false,
    val revoked: Boolean = false,
    val createdAt: String = ""
)
