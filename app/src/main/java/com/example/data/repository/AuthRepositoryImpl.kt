package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.remote.AuthRequest
import com.example.data.remote.SupabaseClient
import com.example.domain.model.UserSession
import com.example.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import com.example.data.remote.SessionDto
import com.example.data.remote.UserDto

class AuthRepositoryImpl(context: Context) : AuthRepository {
    private val sharedPrefs = context.getSharedPreferences("storage_pool_prefs", Context.MODE_PRIVATE)

    private val _currentSession = MutableStateFlow<UserSession?>(null)
    override val currentSession: StateFlow<UserSession?> = _currentSession.asStateFlow()

    init {
        initializeSession()
    }

    override fun initializeSession(): Boolean {
        val token = sharedPrefs.getString("access_token", null)
        val userId = sharedPrefs.getString("user_id", null)
        val email = sharedPrefs.getString("user_email", null)

        val success = if (token != null && userId != null) {
            val session = UserSession(token, userId, email)
            _currentSession.value = session
            SupabaseClient.setSessionToken(token)
            true
        } else {
            _currentSession.value = null
            SupabaseClient.setSessionToken(null)
            false
        }
        Log.d("AuthRepository", "Loaded stored session: $success (User ID: $userId)")
        return success
    }

    override suspend fun signUp(email: String, password: String): Result<UserSession> {
        return try {
            val response = SupabaseClient.authApi.signUp(AuthRequest(email, password))
            if (response.isSuccessful) {
                val rawBody = response.body()?.string() ?: throw Exception("Registration returned empty session body")
                Log.d("AuthRepository", "Sign Up response: $rawBody")

                if (rawBody.contains("access_token")) {
                    val sessionDto = try {
                        val adapter = SupabaseClient.moshi.adapter(SessionDto::class.java)
                        adapter.fromJson(rawBody)
                    } catch (e: Exception) {
                        null
                    }
                    if (sessionDto != null) {
                        val session = UserSession(
                            accessToken = sessionDto.accessToken,
                            userId = sessionDto.user.id,
                            email = sessionDto.user.email ?: email
                        )
                        saveSession(session)
                        return Result.success(session)
                    }
                }

                // If "access_token" is not present, check if it's a valid UserDto (meaning email confirmation on)
                val isUserDto = rawBody.contains("id") && rawBody.contains("email")
                if (isUserDto) {
                    throw Exception("VERIFICATION_REQUIRED")
                } else {
                    throw Exception("Berhasil terdaftar, tetapi tidak ada token akses yang diterima. Silakan cek email Anda untuk verifikasi.")
                }
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Unknown registration failure"
                Log.e("AuthRepository", "Sign Up failed response: $errorMsg")
                val cleanErrorMsg = try {
                    val adapter = SupabaseClient.moshi.adapter(Map::class.java)
                    val map = adapter.fromJson(errorMsg)
                    map?.get("msg")?.toString() ?: errorMsg
                } catch (e: Exception) {
                    errorMsg
                }
                Result.failure(Exception(cleanErrorMsg))
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "Sign Up exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun signIn(email: String, password: String): Result<UserSession> {
        return try {
            val response = SupabaseClient.authApi.signIn(AuthRequest(email, password))
            if (response.isSuccessful) {
                val sessionDto = response.body() ?: throw Exception("Login returned empty session body")
                val session = UserSession(
                    accessToken = sessionDto.accessToken,
                    userId = sessionDto.user.id,
                    email = sessionDto.user.email ?: email
                )
                saveSession(session)
                Result.success(session)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Unknown login failure"
                Log.e("AuthRepository", "Sign In failed response: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "Sign In exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun signOut(): Result<Unit> {
        return try {
            try {
                SupabaseClient.authApi.signOut()
            } catch (e: Exception) {
                Log.w("AuthRepository", "Remote signOut API error, clearing local cache anyway: ${e.message}")
            }
            clearSession()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AuthRepository", "Sign Out error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun saveSession(session: UserSession) {
        sharedPrefs.edit()
            .putString("access_token", session.accessToken)
            .putString("user_id", session.userId)
            .putString("user_email", session.email)
            .apply()
        _currentSession.value = session
        SupabaseClient.setSessionToken(session.accessToken)
    }

    private fun clearSession() {
        sharedPrefs.edit()
            .remove("access_token")
            .remove("user_id")
            .remove("user_email")
            .apply()
        _currentSession.value = null
        SupabaseClient.setSessionToken(null)
    }
}
