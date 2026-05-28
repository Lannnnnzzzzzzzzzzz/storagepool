package com.example.domain.model

data class UserSession(
    val accessToken: String?,
    val userId: String?,
    val email: String?
)
