package com.stegoapp.app.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface StegoApi {
    @GET("/api/v1/stego/algorithms")
    suspend fun getAlgorithms(): Response<AlgorithmsResponse>

    @GET("/api/v1/stego/models")
    suspend fun getModels(
        @Query("algorithm") algorithm: String? = null
    ): Response<ModelsResponse>

    @FormUrlEncoded
    @POST("/api/v1/stego/capacity")
    suspend fun checkCapacity(
        @Field("message") message: String,
        @Field("key") key: String,
        @Field("model") model: String? = null,
        @Field("algorithm") algorithm: String? = null
    ): Response<CapacityResponse>

    @FormUrlEncoded
    @POST("/api/v1/stego/embed")
    suspend fun embed(
        @Field("message") message: String,
        @Field("key") key: String,
        @Field("model") model: String? = null,
        @Field("algorithm") algorithm: String? = null
    ): Response<EmbedResponse>

    @Multipart
    @POST("/api/v1/stego/extract")
    suspend fun extract(
        @Part stego_image: MultipartBody.Part,
        @Part("key") key: RequestBody,
        @Part("model") model: RequestBody? = null,
        @Part("algorithm") algorithm: RequestBody? = null
    ): Response<ExtractResponse>

    @GET("/api/v1/stego/max-capacity")
    suspend fun getMaxCapacity(
        @Query("key") key: String,
        @Query("model") model: String? = null,
        @Query("algorithm") algorithm: String? = null
    ): Response<MaxCapacityResponse>
}
