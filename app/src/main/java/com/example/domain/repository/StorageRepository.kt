package com.example.domain.repository

import com.example.domain.model.StorageBucket
import com.example.domain.model.CloudFile
import kotlinx.coroutines.flow.Flow

interface StorageRepository {
    suspend fun fetchBuckets(): Result<List<StorageBucket>>
    suspend fun fetchFiles(): Result<List<CloudFile>>
    
    fun uploadFile(
        filename: String,
        filePath: String,
        fileSize: Long,
        mimeType: String,
        fileBytes: ByteArray,
        isEncrypted: Boolean
    ): Flow<UploadStatus>

    suspend fun deleteFile(fileId: String, bucketId: String, filePath: String): Result<Unit>
}

sealed class UploadStatus {
    object Idle : UploadStatus()
    data class Progress(val percentage: Float, val currentBucketName: String) : UploadStatus()
    data class Success(val file: CloudFile) : UploadStatus()
    data class Error(val message: String) : UploadStatus()
}
