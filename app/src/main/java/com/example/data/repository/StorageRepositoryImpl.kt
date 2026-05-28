package com.example.data.repository

import android.util.Log
import com.example.data.remote.BucketSizeUpdateDto
import com.example.data.remote.FileDto
import com.example.data.remote.SupabaseClient
import com.example.data.util.S3Signer
import com.example.domain.model.CloudFile
import com.example.domain.model.StorageBucket
import com.example.domain.repository.StorageRepository
import com.example.domain.repository.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class StorageRepositoryImpl : StorageRepository {

    private val httpClient = OkHttpClient.Builder().build()
    private val KEY_ALGORITHM = "AES"
    private val CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding"

    override suspend fun fetchBuckets(): Result<List<StorageBucket>> {
        return try {
            val response = SupabaseClient.dbApi.getBuckets()
            if (response.isSuccessful) {
                val bucketDtos = response.body() ?: emptyList()
                val buckets = bucketDtos.map { dto ->
                    StorageBucket(
                        id = dto.id,
                        bucketName = dto.bucketName,
                        endpoint = dto.endpoint,
                        accessKeyId = dto.accessKeyId,
                        secretAccessKey = dto.secretAccessKey,
                        totalQuotaBytes = dto.totalQuotaBytes,
                        usedBytes = dto.usedBytes,
                        status = dto.status
                    )
                }
                Result.success(buckets)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Failed to fetch buckets"
                Log.e("StorageRepository", "fetchBuckets error: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("StorageRepository", "fetchBuckets exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun fetchFiles(): Result<List<CloudFile>> {
        return try {
            val response = SupabaseClient.dbApi.getFiles()
            if (response.isSuccessful) {
                val fileDtos = response.body() ?: emptyList()
                val files = fileDtos.map { dto ->
                    CloudFile(
                        id = dto.id.orEmpty(),
                        userId = dto.userId.orEmpty(),
                        filename = dto.filename,
                        filePath = dto.filePath,
                        fileSize = dto.fileSize,
                        mimeType = dto.mimeType,
                        bucketId = dto.bucketId,
                        isEncrypted = dto.isEncrypted
                    )
                }
                Result.success(files)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Failed to fetch files"
                Log.e("StorageRepository", "fetchFiles error: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("StorageRepository", "fetchFiles exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun uploadFile(
        filename: String,
        filePath: String,
        fileSize: Long,
        mimeType: String,
        fileBytes: ByteArray,
        isEncrypted: Boolean
    ): Flow<UploadStatus> = flow {
        emit(UploadStatus.Idle)

        // 1. Fetch available buckets to build the smart storage pool
        val bucketsResult = fetchBuckets()
        if (bucketsResult.isFailure) {
            emit(UploadStatus.Error("Failed to fetch storage buckets configuration: ${bucketsResult.exceptionOrNull()?.message}"))
            return@flow
        }

        val allBuckets = bucketsResult.getOrDefault(emptyList())
        
        // 2. Filter buckets that are ACTIVE and have enough available capacity
        val eligibleBuckets = allBuckets.filter { bucket ->
            bucket.status.equals("ACTIVE", ignoreCase = true) && bucket.availableSpaceBytes >= fileBytes.size
        }.sortedBy { it.usedBytes } // Smart Routing: Optimal bucket (least used) is first!

        if (eligibleBuckets.isEmpty()) {
            emit(UploadStatus.Error("No available buckets in pool. Either all buckets are full/down or file size exceeds available pool space."))
            return@flow
        }

        // Preparation: handle client side encryption if isEncrypted is enabled
        var finalBytesToUpload = fileBytes
        if (isEncrypted) {
            try {
                finalBytesToUpload = encryptBytes(fileBytes)
                Log.d("StorageRepository", "Encrypting file. Size inflated from ${fileBytes.size} to ${finalBytesToUpload.size} bytes.")
            } catch (encryptEx: Exception) {
                emit(UploadStatus.Error("Encryption failed: ${encryptEx.message}"))
                return@flow
            }
        }

        // 3. Failover Retrying Loop: Loop through buckets until upload succeeds
        var uploadSuccess = false
        var lastError: String = "Unknown upload pool error"

        for (bucket in eligibleBuckets) {
            Log.d("StorageRepository", "Attempt routing to Buckets Pool: '${bucket.bucketName}' (Available space: ${bucket.availableSpaceBytes} bytes)")
            
            try {
                emit(UploadStatus.Progress(0.01f, bucket.bucketName))

                // Generate Presigned S3 PUT URL for Cloudflare R2
                val presignedUrl = S3Signer.generatePresignedUrl(
                    endpoint = bucket.endpoint,
                    bucketName = bucket.bucketName,
                    filePath = filePath,
                    accessKeyId = bucket.accessKeyId,
                    secretAccessKey = bucket.secretAccessKey
                )

                // Binary PUT stream using customized OkHttp streaming request body with live progress reporting
                val targetMediaType = mimeType.toMediaTypeOrNull()
                val streamingBody = StreamingRequestBody(targetMediaType, finalBytesToUpload) { progressFraction ->
                    // Guard progress bounds
                    val verifiedProgress = progressFraction.coerceIn(0.01f, 0.99f)
                    // Emit progress
                    runBlocking {
                        emit(UploadStatus.Progress(verifiedProgress, bucket.bucketName))
                    }
                }

                val putRequest = Request.Builder()
                    .url(presignedUrl)
                    .put(streamingBody)
                    .build()

                val callResponse = httpClient.newCall(putRequest).execute()
                
                if (callResponse.isSuccessful) {
                    Log.d("StorageRepository", "Binary chunk successfully sent to R2 bucket '${bucket.bucketName}'!")
                    
                    // Create object metadata
                    val fileDto = FileDto(
                        filename = filename,
                        filePath = filePath,
                        fileSize = finalBytesToUpload.size.toLong(),
                        mimeType = mimeType,
                        bucketId = bucket.id,
                        isEncrypted = isEncrypted
                    )

                    // Synchronize metadata in Supabase files repository
                    val insertResponse = SupabaseClient.dbApi.insertFile(fileDto)
                    if (insertResponse.isSuccessful) {
                        val savedDtoList = insertResponse.body()
                        // Some endpoints might return single object or the representation array, handle safely
                        val savedFileDto = savedDtoList ?: fileDto

                        // Update local bucket storage allocation in primary Supabase buckets register
                        val updatedBytes = bucket.usedBytes + finalBytesToUpload.size
                        val updateResponse = SupabaseClient.dbApi.updateBucketUsedSize(
                            idFilter = "eq.${bucket.id}",
                            update = BucketSizeUpdateDto(usedBytes = updatedBytes)
                        )
                        if (!updateResponse.isSuccessful) {
                            Log.w("StorageRepository", "Metadata synced, but bucket capacity registry update failed: ${updateResponse.errorBody()?.string()}")
                        }

                        // Success! Transition state
                        val finalCloudFile = CloudFile(
                            id = savedFileDto.id.orEmpty(),
                            userId = savedFileDto.userId.orEmpty(),
                            filename = savedFileDto.filename,
                            filePath = savedFileDto.filePath,
                            fileSize = savedFileDto.fileSize,
                            mimeType = savedFileDto.mimeType,
                            bucketId = savedFileDto.bucketId,
                            isEncrypted = savedFileDto.isEncrypted
                        )
                        emit(UploadStatus.Progress(1.0f, bucket.bucketName))
                        emit(UploadStatus.Success(finalCloudFile))
                        uploadSuccess = true
                        break // Break retrying loop on successful deployment!
                    } else {
                        val dbError = insertResponse.errorBody()?.string() ?: "Metadata insertion error"
                        throw Exception("Database synchronization failed: $dbError")
                    }
                } else {
                    val code = callResponse.code
                    val msg = callResponse.message
                    throw Exception("S3/R2 Server rejected stream payload (HTTP $code: $msg)")
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Upload streaming error"
                Log.e("StorageRepository", "Routing upload stream failure on '${bucket.bucketName}': $lastError. Re-routing failover stream to next candidate...", e)
                // Continue loop with flag to let next bucket handle it!
            }
        }

        if (!uploadSuccess) {
            emit(UploadStatus.Error("Failed to upload file. Retried all optimal buckets. Last Error: $lastError"))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun deleteFile(fileId: String, bucketId: String, filePath: String): Result<Unit> {
        return try {
            // First select the bucket to get connection credentials to delete it from S3 as well
            val bucketsResult = fetchBuckets()
            if (bucketsResult.isSuccess) {
                val bucketObj = bucketsResult.getOrNull()?.find { it.id == bucketId }
                if (bucketObj != null) {
                    try {
                        // Attempt DELETE from R2 using direct API delete
                        // S3 V4 Delete request is similar to PUT but with DELETE method.
                        // For extreme robustness, if R2 delete fails or is unauthorized, we proceed with deleting metadata from Postgres!
                        val deleteUrl = S3Signer.generatePresignedUrl(
                            endpoint = bucketObj.endpoint,
                            bucketName = bucketObj.bucketName,
                            filePath = filePath,
                            accessKeyId = bucketObj.accessKeyId,
                            secretAccessKey = bucketObj.secretAccessKey
                        ).replace("X-Amz-Expires=3600", "X-Amz-Expires=600") // lower expiry for custom deletion method
                        
                        val httpDelete = Request.Builder()
                            .url(deleteUrl)
                            .delete()
                            .build()
                        val deleteResponse = httpClient.newCall(httpDelete).execute()
                        Log.d("StorageRepository", "S3 file removal status on Cloudflare: ${deleteResponse.isSuccessful}")
                    } catch (s3Ex: Exception) {
                        Log.e("StorageRepository", "R2 object removal failed: ${s3Ex.message}, progressing to delete DB record anyway", s3Ex)
                    }
                }
            }

            // Remove metadata from PostgreSQL in Supabase
            val response = SupabaseClient.dbApi.deleteFile("eq.$fileId")
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Failed to delete file from Database"
                Log.e("StorageRepository", "deleteFile error: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("StorageRepository", "deleteFile exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    // --- Helper client side AES Encryption ---
    private fun encryptBytes(plainBytes: ByteArray): ByteArray {
        val rawKey = SupabaseClient.supabaseAnonKey.take(16).padEnd(16, 'x').toByteArray(Charsets.UTF_8)
        val secretKeySpec = SecretKeySpec(rawKey, KEY_ALGORITHM)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)
        val encryptedBytes = cipher.doFinal(plainBytes)
        
        // Append IV to the beginning of the bytes to safely decode on recovery later
        val totalPayload = ByteArray(16 + encryptedBytes.size)
        System.arraycopy(iv, 0, totalPayload, 0, 16)
        System.arraycopy(encryptedBytes, 0, totalPayload, 16, encryptedBytes.size)
        return totalPayload
    }

    // Custom Streaming RequestBody to track progress elegantly
    private class StreamingRequestBody(
        private val contentType: okhttp3.MediaType?,
        private val fileBytes: ByteArray,
        private val onProgress: (progress: Float) -> Unit
    ) : RequestBody() {

        override fun contentType(): okhttp3.MediaType? = contentType

        override fun contentLength(): Long {
            return fileBytes.size.toLong()
        }

        override fun writeTo(sink: BufferedSink) {
            val totalBytes = fileBytes.size.toLong()
            var bytesWritten = 0L
            val buffer = ByteArray(8192)
            val inputStream = ByteArrayInputStream(fileBytes)

            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                bytesWritten += read
                onProgress(bytesWritten.toFloat() / totalBytes.toFloat())
            }
        }
    }
}
