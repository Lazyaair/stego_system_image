package com.stegoapp.app.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface StegoApi {
    @Multipart
    @POST("/api/v1/stego/embed")
    suspend fun embed(
        @Part cover_image: MultipartBody.Part,
        @Part("secret_message") message: RequestBody,
        @Part("key") key: RequestBody,
        @Part("embed_rate") embedRate: RequestBody
    ): Response<EmbedResponse>

    @Multipart
    @POST("/api/v1/stego/extract")
    suspend fun extract(
        @Part stego_image: MultipartBody.Part,
        @Part("key") key: RequestBody
    ): Response<ExtractResponse>
}
