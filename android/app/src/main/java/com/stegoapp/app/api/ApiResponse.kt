package com.stegoapp.app.api

data class EmbedResponse(
    val status: String,
    val stego_image: String?,
    val is_demo: Boolean
)

data class ExtractResponse(
    val status: String,
    val secret_message: String?,
    val is_demo: Boolean
)
