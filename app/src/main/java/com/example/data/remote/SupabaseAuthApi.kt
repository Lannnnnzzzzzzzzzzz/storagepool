package com.example.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface SupabaseAuthApi {
    @POST("auth/v1/signup")
    suspend fun signUp(@Body request: AuthRequest): Response<SessionDto>

    @POST("auth/v1/token?grant_type=password")
    suspend fun signIn(@Body request: AuthRequest): Response<SessionDto>

    @POST("auth/v1/logout")
    suspend fun signOut(): Response<Unit>
}
