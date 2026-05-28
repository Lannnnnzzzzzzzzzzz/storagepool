package com.example.domain.model

data class CloudFile(
    val id: String,
    val userId: String,
    val filename: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val bucketId: String,
    val isEncrypted: Boolean
)
