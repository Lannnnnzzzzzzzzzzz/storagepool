package com.example.domain.repository

import com.example.domain.model.UserSession
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val currentSession: StateFlow<UserSession?>
    suspend fun signUp(email: String, password: String): Result<UserSession>
    suspend fun signIn(email: String, password: String): Result<UserSession>
    suspend fun signOut(): Result<Unit>
    fun initializeSession(): Boolean
}
