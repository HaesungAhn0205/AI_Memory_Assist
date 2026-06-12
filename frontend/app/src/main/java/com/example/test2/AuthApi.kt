package com.example.test2

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class LoginRequest(
    val user_name: String,
    val user_pw: String,
)

data class LoginResponse(
    val result: String,
    val message: String,
    val user_id: String? = null,
    val user_name: String? = null,
)

interface AuthApiService {
    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>
}
