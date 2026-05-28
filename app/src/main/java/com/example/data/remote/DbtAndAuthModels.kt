package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// === Supabase Auth Models ===

@JsonClass(generateAdapter = true)
data class AuthRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class UserDto(
    @Json(name = "id") val id: String,
    @Json(name = "email") val email: String?
)

@JsonClass(generateAdapter = true)
data class SessionDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "user") val user: UserDto
)

// === Supabase Database DTOs ===

@JsonClass(generateAdapter = true)
data class BucketDto(
    @Json(name = "id") val id: String,
    @Json(name = "bucket_name") val bucketName: String,
    @Json(name = "endpoint") val endpoint: String,
    @Json(name = "access_key_id") val accessKeyId: String,
    @Json(name = "secret_access_key") val secretAccessKey: String,
    @Json(name = "total_quota_bytes") val totalQuotaBytes: Long,
    @Json(name = "used_bytes") val usedBytes: Long,
    @Json(name = "status") val status: String
)

@JsonClass(generateAdapter = true)
data class FileDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "filename") val filename: String,
    @Json(name = "file_path") val filePath: String,
    @Json(name = "file_size") val fileSize: Long,
    @Json(name = "mime_type") val mimeType: String,
    @Json(name = "bucket_id") val bucketId: String,
    @Json(name = "is_encrypted") val isEncrypted: Boolean
)

// === Database Update DTO ===
@JsonClass(generateAdapter = true)
data class BucketSizeUpdateDto(
    @Json(name = "used_bytes") val usedBytes: Long
)
