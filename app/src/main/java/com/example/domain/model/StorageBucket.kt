package com.example.domain.model

data class StorageBucket(
    val id: String,
    val bucketName: String,
    val endpoint: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val totalQuotaBytes: Long,
    val usedBytes: Long,
    val status: String // "ACTIVE", "FULL", "DOWN"
) {
    val availableSpaceBytes: Long
        get() = (totalQuotaBytes - usedBytes).coerceAtLeast(0)
    
    val usedPercentage: Float
        get() = if (totalQuotaBytes > 0) usedBytes.toFloat() / totalQuotaBytes.toFloat() else 0f
}
