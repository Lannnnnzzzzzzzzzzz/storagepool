package com.example.data.repository

import android.util.Log
import com.example.data.remote.BucketDto
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
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    override suspend fun fetchBuckets(): Result<List<StorageBucket>> = withContext(Dispatchers.IO) {
        try {
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

    override suspend fun fetchFiles(): Result<List<CloudFile>> = withContext(Dispatchers.IO) {
        try {
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
    ): Flow<UploadStatus> = channelFlow {
        send(UploadStatus.Idle)

        // 1. Fetch available buckets to build the smart storage pool
        val bucketsResult = fetchBuckets()
        if (bucketsResult.isFailure) {
            send(UploadStatus.Error("Failed to fetch storage buckets configuration: ${bucketsResult.exceptionOrNull()?.message}"))
            return@channelFlow
        }

        val allBuckets = bucketsResult.getOrDefault(emptyList())
        
        // 2. Filter buckets that are ACTIVE and have enough available capacity
        val eligibleBuckets = allBuckets.filter { bucket ->
            bucket.status.equals("ACTIVE", ignoreCase = true) && bucket.availableSpaceBytes >= fileBytes.size
        }.sortedBy { it.usedBytes } // Smart Routing: Optimal bucket (least used) is first!

        if (eligibleBuckets.isEmpty()) {
            send(UploadStatus.Error("No available buckets in pool. Either all buckets are full/down or file size exceeds available pool space."))
            return@channelFlow
        }

        // Preparation: handle client side encryption if isEncrypted is enabled
        var finalBytesToUpload = fileBytes
        if (isEncrypted) {
            try {
                finalBytesToUpload = encryptBytes(fileBytes)
                Log.d("StorageRepository", "Encrypting file. Size inflated from ${fileBytes.size} to ${finalBytesToUpload.size} bytes.")
            } catch (encryptEx: Exception) {
                send(UploadStatus.Error("Encryption failed: ${encryptEx.message}"))
                return@channelFlow
            }
        }

        // 3. Failover Retrying Loop: Loop through buckets until upload succeeds
        var uploadSuccess = false
        var lastError: String = "Unknown upload pool error"

        for (bucket in eligibleBuckets) {
            Log.d("StorageRepository", "Attempt routing to Buckets Pool: '${bucket.bucketName}' (Available space: ${bucket.availableSpaceBytes} bytes)")
            
            try {
                send(UploadStatus.Progress(0.01f, bucket.bucketName))

                // Normalize mimeType to prevent cases and blank mismatch errors
                val resolvedMimeType = if (mimeType.isNullOrBlank()) "application/octet-stream" else mimeType.trim().lowercase()

                // Generate Presigned S3 PUT URL for Cloudflare R2
                val presignedUrl = S3Signer.generatePresignedUrl(
                    endpoint = bucket.endpoint,
                    bucketName = bucket.bucketName,
                    filePath = filePath,
                    accessKeyId = bucket.accessKeyId,
                    secretAccessKey = bucket.secretAccessKey,
                    contentType = resolvedMimeType // Sign content-type so it matches OkHttp's headers perfectly
                )

                // Binary PUT stream using customized OkHttp streaming request body with live progress reporting
                val targetMediaType = resolvedMimeType.toMediaTypeOrNull()
                val streamingBody = StreamingRequestBody(targetMediaType, finalBytesToUpload) { progressFraction ->
                    // Guard progress bounds
                    val verifiedProgress = progressFraction.coerceIn(0.01f, 0.99f)
                    // Send progress safely
                    trySend(UploadStatus.Progress(verifiedProgress, bucket.bucketName))
                }

                val putRequest = Request.Builder()
                    .url(presignedUrl)
                    .header("Content-Type", resolvedMimeType)
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
                        mimeType = resolvedMimeType,
                        bucketId = bucket.id,
                        isEncrypted = isEncrypted
                    )

                    // Synchronize metadata in Supabase files repository
                    val insertResponse = SupabaseClient.dbApi.insertFile(fileDto)
                    if (insertResponse.isSuccessful) {
                        val savedDtoList = insertResponse.body()
                        val savedFileDto = savedDtoList?.firstOrNull() ?: fileDto

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
                        send(UploadStatus.Progress(1.0f, bucket.bucketName))
                        send(UploadStatus.Success(finalCloudFile))
                        uploadSuccess = true
                        break // Break retrying loop on successful deployment!
                    } else {
                        val dbError = insertResponse.errorBody()?.string() ?: "Metadata insertion error"
                        throw Exception("Database synchronization failed: $dbError")
                    }
                } else {
                    val code = callResponse.code
                    val msg = callResponse.message
                    val errorBody = callResponse.body?.string() ?: ""
                    Log.e("StorageRepository", "R2 S3 API rejected stream! HTTP Code $code: $msg. Response Body: $errorBody")
                    throw Exception("S3/R2 Server rejected stream payload (HTTP $code: $msg). Details: $errorBody")
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Upload streaming error"
                Log.e("StorageRepository", "Routing upload stream failure on '${bucket.bucketName}': $lastError. Re-routing failover stream to next candidate...", e)
                // Continue loop with flag to let next bucket handle it!
            }
        }

        if (!uploadSuccess) {
            send(UploadStatus.Error("Failed to upload file. Retried all optimal buckets. Last Error: $lastError"))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun deleteFile(fileId: String, bucketId: String, filePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
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
                            secretAccessKey = bucketObj.secretAccessKey,
                            expiresSeconds = 600,
                            method = "DELETE"
                        )
                        
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

    override suspend fun deleteBucket(bucketId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = SupabaseClient.dbApi.deleteBucket("eq.$bucketId")
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Gagal menghapus Node dari basis data Supabase"
                Log.e("StorageRepository", "deleteBucket error: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("StorageRepository", "deleteBucket exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun addBucket(
        bucketName: String,
        endpoint: String,
        accessKeyId: String,
        secretAccessKey: String,
        totalQuotaBytes: Long
    ): Result<StorageBucket> = withContext(Dispatchers.IO) {
        try {
            // Clean inputs (extremely helpful to auto-remove leading/trailing spaces and pasted single/double quotes)
            val cleanBucketName = bucketName.trim().removeSurrounding("'").removeSurrounding("\"").trim()
            var cleanEndpoint = endpoint.trim().removeSurrounding("'").removeSurrounding("\"").trim()
            if (!cleanEndpoint.startsWith("http://") && !cleanEndpoint.startsWith("https://")) {
                cleanEndpoint = "https://$cleanEndpoint"
            }
            val cleanAccessKeyId = accessKeyId.trim().removeSurrounding("'").removeSurrounding("\"").trim()
            val cleanSecretAccessKey = secretAccessKey.trim().removeSurrounding("'").removeSurrounding("\"").trim()

            // 1. Verifikasi kredensial AWS S3 / Cloudflare R2 dengan menaruh sepotong data uji
            val testFilePath = ".pool_validation_test_${java.util.UUID.randomUUID().toString().take(6)}"
            val testBytes = "R2-VALIDATION-VERIFY-OK".toByteArray()
            
            try {
                // Buat presigned URL untuk verifikasi PUT
                val testPutUrl = S3Signer.generatePresignedUrl(
                    endpoint = cleanEndpoint,
                    bucketName = cleanBucketName,
                    filePath = testFilePath,
                    accessKeyId = cleanAccessKeyId,
                    secretAccessKey = cleanSecretAccessKey,
                    method = "PUT",
                    contentType = "text/plain"
                )
                
                val testRequestBody = object : RequestBody() {
                    val mediaType = "text/plain".toMediaTypeOrNull()
                    override fun contentType() = mediaType
                    override fun contentLength() = testBytes.size.toLong()
                    override fun writeTo(sink: okio.BufferedSink) {
                        sink.write(testBytes)
                    }
                }
                
                val testPutRequest = Request.Builder()
                    .url(testPutUrl)
                    .header("Content-Type", "text/plain")
                    .put(testRequestBody)
                    .build()
                
                httpClient.newCall(testPutRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val responseBody = response.body?.string().orEmpty()
                        throw Exception("Endpoint S3/R2 mengembalikan kode status error HTTP ${response.code}. Detail: $responseBody")
                    }
                }
                
                // Jika PUT berhasil, hapus kembali file sementara agar bucket tetap bersih
                try {
                    val testDeleteUrl = S3Signer.generatePresignedUrl(
                        endpoint = cleanEndpoint,
                        bucketName = cleanBucketName,
                        filePath = testFilePath,
                        accessKeyId = cleanAccessKeyId,
                        secretAccessKey = cleanSecretAccessKey,
                        method = "DELETE"
                    )
                    val testDeleteRequest = Request.Builder()
                        .url(testDeleteUrl)
                        .delete()
                        .build()
                    httpClient.newCall(testDeleteRequest).execute().close()
                } catch (delEx: Exception) {
                    Log.w("StorageRepository", "Gagal membersihkan berkas uji validasi: ${delEx.message}")
                }
            } catch (e: Exception) {
                Log.e("StorageRepository", "Gagal mendaftarkan Node: R2/S3 tidak valid", e)
                val cleanMessage = when {
                    e.message?.contains("Unable to resolve host") == true || e.message?.contains("No address associated with hostname") == true -> {
                        "S3 Error: Unable to resolve host (Endpoint URL salah)."
                    }
                    e.message?.contains("403") == true -> {
                        "S3 Error: HTTP 403 Access Denied (Access Key / Secret Key salah)."
                    }
                    e.message?.contains("404") == true -> {
                        "S3 Error: HTTP 404 Not Found (Nama/ID Bucket tidak ditemukan)."
                    }
                    else -> {
                        e.message ?: "Koneksi ke endpoint R2/S3 ditolak."
                    }
                }
                return@withContext Result.failure(Exception(
                    "Verifikasi gagal: $cleanMessage\nSilakan cek kembali kredensial atau pengaturan CORS Anda."
                ))
            }

            // 2. Jika validasi berhasil, simpan ke Database Supabase
            val bucketDto = BucketDto(
                id = java.util.UUID.randomUUID().toString(),
                bucketName = cleanBucketName,
                endpoint = cleanEndpoint,
                accessKeyId = cleanAccessKeyId,
                secretAccessKey = cleanSecretAccessKey,
                totalQuotaBytes = totalQuotaBytes,
                usedBytes = 0,
                status = "ACTIVE"
            )
            val response = SupabaseClient.dbApi.insertBucket(bucketDto)
            if (response.isSuccessful) {
                val inserted = response.body()?.firstOrNull() ?: bucketDto
                Result.success(
                    StorageBucket(
                        id = inserted.id,
                        bucketName = inserted.bucketName,
                        endpoint = inserted.endpoint,
                        accessKeyId = inserted.accessKeyId,
                        secretAccessKey = inserted.secretAccessKey,
                        totalQuotaBytes = inserted.totalQuotaBytes,
                        usedBytes = inserted.usedBytes,
                        status = inserted.status
                    )
                )
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Gagal mendaftarkan Node ke basis data Supabase"
                Log.e("StorageRepository", "addBucket error: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("StorageRepository", "addBucket exception: ${e.message}", e)
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

    override suspend fun downloadFile(file: CloudFile): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val bucketsResult = fetchBuckets()
            if (bucketsResult.isFailure) {
                return@withContext Result.failure(Exception("Gagal mengambil konfigurasi node pool: ${bucketsResult.exceptionOrNull()?.message}"))
            }
            val bucket = bucketsResult.getOrDefault(emptyList()).find { it.id == file.bucketId }
                ?: return@withContext Result.failure(Exception("Node pool penyimpanan (ID: ${file.bucketId}) tidak ditemukan!"))

            val getUrl = S3Signer.generatePresignedUrl(
                endpoint = bucket.endpoint,
                bucketName = bucket.bucketName,
                filePath = file.filePath,
                accessKeyId = bucket.accessKeyId,
                secretAccessKey = bucket.secretAccessKey,
                expiresSeconds = 3600,
                method = "GET"
            )

            val request = Request.Builder().url(getUrl).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Koneksi ditolak oleh node pool S3/R2 (HTTP ${response.code}: ${response.message})"))
            }

            val bodyBytes = response.body?.bytes() ?: return@withContext Result.failure(Exception("Konten data kosong"))

            if (file.isEncrypted) {
                try {
                    val decrypted = decryptBytes(bodyBytes)
                    Result.success(decrypted)
                } catch (decEx: Exception) {
                    Result.failure(Exception("Dekripsi gagal. Sesi sinkronisasi tidak valid atau file rusak. Detail: ${decEx.message}"))
                }
            } else {
                Result.success(bodyBytes)
            }
        } catch (e: Exception) {
            Log.e("StorageRepository", "downloadFile exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun generateShareUrl(file: CloudFile): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bucketsResult = fetchBuckets()
            if (bucketsResult.isFailure) {
                return@withContext Result.failure(Exception("Gagal memuat node pool: ${bucketsResult.exceptionOrNull()?.message}"))
            }
            val bucket = bucketsResult.getOrDefault(emptyList()).find { it.id == file.bucketId }
                ?: return@withContext Result.failure(Exception("Node pool untuk file tidak ditemukan"))

            val expiresSeconds = 604800L // 7 Hari
            val shareUrl = S3Signer.generatePresignedUrl(
                endpoint = bucket.endpoint,
                bucketName = bucket.bucketName,
                filePath = file.filePath,
                accessKeyId = bucket.accessKeyId,
                secretAccessKey = bucket.secretAccessKey,
                expiresSeconds = expiresSeconds,
                method = "GET"
            )
            Result.success(shareUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun decryptBytes(encryptedBytes: ByteArray): ByteArray {
        val rawKey = SupabaseClient.supabaseAnonKey.take(16).padEnd(16, 'x').toByteArray(Charsets.UTF_8)
        val secretKeySpec = SecretKeySpec(rawKey, KEY_ALGORITHM)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        
        if (encryptedBytes.size < 16) {
            throw Exception("Payload enkripsi tidak valid")
        }
        val iv = ByteArray(16)
        System.arraycopy(encryptedBytes, 0, iv, 0, 16)
        val ivSpec = IvParameterSpec(iv)
        
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
        val contentBytes = ByteArray(encryptedBytes.size - 16)
        System.arraycopy(encryptedBytes, 16, contentBytes, 0, contentBytes.size)
        return cipher.doFinal(contentBytes)
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
