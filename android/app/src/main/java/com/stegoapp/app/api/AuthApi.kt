package com.stegoapp.app.api

import retrofit2.http.*

interface AuthApi {
    @POST("/api/v1/auth/register")
    suspend fun register(@Body request: AuthRequest): AuthResponse

    @POST("/api/v1/auth/login")
    suspend fun login(@Body request: AuthRequest): AuthResponse

    @GET("/api/v1/auth/me")
    suspend fun me(): UserInfoResponse

    @POST("/api/v1/auth/logout")
    suspend fun logout(): MessageResponse
}
