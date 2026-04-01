package com.stegoapp.app.api

import retrofit2.http.*

interface InviteApi {
    @GET("/api/v1/invite/my-code")
    suspend fun getMyCode(): InviteCodeResponse

    @POST("/api/v1/invite/reset")
    suspend fun resetCode(): InviteCodeResponse

    @GET("/api/v1/invite/{code}")
    suspend fun lookupCode(@Path("code") code: String): InviteLookupResponse
}
