package com.stegoapp.app.api

import android.content.Context
import com.stegoapp.app.data.local.TokenStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val BASE_URL = "http://10.0.2.2:8000/"

    private var tokenStore: TokenStore? = null
    private var retrofit: Retrofit? = null

    fun init(context: Context) {
        tokenStore = TokenStore(context)
        retrofit = createRetrofit()
    }

    private fun createRetrofit(): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val authInterceptor = Interceptor { chain ->
            val token = runBlocking { tokenStore?.token?.firstOrNull() }
            val request = if (token != null) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val stegoApi: StegoApi by lazy { retrofit!!.create(StegoApi::class.java) }
    val authApi: AuthApi by lazy { retrofit!!.create(AuthApi::class.java) }
    val inviteApi: InviteApi by lazy { retrofit!!.create(InviteApi::class.java) }

    fun getBaseUrl(): String = BASE_URL
    fun getTokenStore(): TokenStore? = tokenStore
}
