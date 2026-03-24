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
