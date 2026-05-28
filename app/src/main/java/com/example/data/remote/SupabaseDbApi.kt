package com.example.data.remote

import retrofit2.Response
import retrofit2.http.*

interface SupabaseDbApi {
    @GET("rest/v1/storage_buckets")
    suspend fun getBuckets(): Response<List<BucketDto>>

    @PATCH("rest/v1/storage_buckets")
    suspend fun updateBucketUsedSize(
        @Query("id") idFilter: String, // e.g. "eq.uuid"
        @Body update: BucketSizeUpdateDto
    ): Response<Unit>

    @GET("rest/v1/files")
    suspend fun getFiles(): Response<List<FileDto>>

    @POST("rest/v1/files")
    suspend fun insertFile(@Body file: FileDto): Response<FileDto>

    @DELETE("rest/v1/files")
    suspend fun deleteFile(
        @Query("id") idFilter: String // e.g. "eq.uuid"
    ): Response<Unit>
}
