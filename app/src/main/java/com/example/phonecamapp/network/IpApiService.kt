package com.example.phonecamapp.network

import kotlinx.serialization.Serializable
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query

// --- RETROFIT ---

@Serializable
data class IpResponse(
    val ip: String
)

interface IpApiService {
    @GET("/")
    suspend fun getPublicIp(@Query("format") format: String = "json"): IpResponse
}

object RetrofitInstance {
    private const val BASE_URL = "https://api.ipify.org/"
    private val json = Json { ignoreUnknownKeys = true }

    val api: IpApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(IpApiService::class.java)
    }
}