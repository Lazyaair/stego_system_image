package com.stegoapp.app.api

data class Model(
    val id: String,
    val name: String,
    val default: Boolean
)

data class ModelsResponse(
    val models: List<Model>
)

data class CapacityResponse(
    val valid: Boolean,
    val message_length: Int,
    val max_capacity: Int,
    val error: String?
)

data class MaxCapacityResponse(
    val max_capacity: Int
)

data class UserCodeResponse(
    val code: String,
    val user_id: String,
    val username: String
)

data class EmbedResponse(
    val status: String,
    val stego_image: String?,
    val model: String?,
    val message_length: Int?,
    val error: String?,
    val max_capacity: Int?,
    val is_demo: Boolean
)

data class ExtractResponse(
    val status: String,
    val secret_message: String?,
    val model: String?,
    val error: String?,
    val is_demo: Boolean
)

data class AuthResponse(
    val user_id: String,
    val username: String,
    val token: String
)

data class UserInfoResponse(
    val user_id: String,
    val username: String,
    val created_at: String? = null
)

data class InviteCodeResponse(
    val code: String,
    val link: String,
    val created_at: String?
)

data class InviteLookupResponse(
    val user_id: String,
    val username: String
)

data class AuthRequest(
    val username: String,
    val password: String
)

data class MessageResponse(
    val message: String
)
