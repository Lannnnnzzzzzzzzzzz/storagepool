package com.example.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.CloudFile
import com.example.domain.model.StorageBucket
import com.example.domain.repository.StorageRepository
import com.example.domain.repository.UploadStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class DashboardState(
    val buckets: List<StorageBucket> = emptyList(),
    val allFiles: List<CloudFile> = emptyList(),
    val currentFolderFiles: List<CloudFile> = emptyList(),
    val currentSubfolders: List<String> = emptyList(),
    val currentPath: String = "", // e.g. "" or "documents" or "photos/vacation"
    val isLoading: Boolean = false,
    val error: String? = null,
    
    // Upload state tracking
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val uploadBucketName: String = "",
    val uploadFailovers: List<String> = emptyList(), // History of failed buckets for active session
    val uploadStatusMessage: String? = null
) {
    val totalCapacityBytes: Long
        get() = buckets.sumOf { it.totalQuotaBytes }

    val usedSpaceBytes: Long
        get() = buckets.sumOf { it.usedBytes }

    val aggregatePercentage: Float
        get() = if (totalCapacityBytes > 0) usedSpaceBytes.toFloat() / totalCapacityBytes.toFloat() else 0f
}

class DashboardViewModel(
    private val storageRepository: StorageRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var isDemoMode: Boolean = false

    fun setDemoMode(enabled: Boolean) {
        this.isDemoMode = enabled
        loadStoragePool()
    }

    fun loadStoragePool() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            if (isDemoMode) {
                // Pre-populate beautiful mock Cloud storage buckets pool & files for Simulation Mode
                delay(800) // Aesthetic delay as per styling guidelines
                val mockBuckets = listOf(
                    StorageBucket(
                        id = "bucket-1",
                        bucketName = "cloudflare-r2-us-west",
                        endpoint = "https://r2-accounts-west.cloudflarestorage.com",
                        accessKeyId = "ak_west_***_xyz",
                        secretAccessKey = "sk_west_***",
                        totalQuotaBytes = 10L * 1024L * 1024L * 1024L, // 10 GB
                        usedBytes = 4L * 1024L * 1024L * 1024L, // 4 GB used
                        status = "ACTIVE"
                    ),
                    StorageBucket(
                        id = "bucket-2",
                        bucketName = "cloudflare-r2-eu-central",
                        endpoint = "https://r2-accounts-eu.cloudflarestorage.com",
                        accessKeyId = "ak_eu_***_abc",
                        secretAccessKey = "sk_eu_***",
                        totalQuotaBytes = 15L * 1024L * 1024L * 1024L, // 15 GB
                        usedBytes = 11L * 1024L * 1024L * 1024L, // 11 GB used (Near Full!)
                        status = "ACTIVE"
                    ),
                    StorageBucket(
                        id = "bucket-3",
                        bucketName = "cloudflare-r2-asia-east",
                        endpoint = "https://r2-accounts-asia.cloudflarestorage.com",
                        accessKeyId = "ak_asia_***_mno",
                        secretAccessKey = "sk_asia_***",
                        totalQuotaBytes = 5L * 1024L * 1024L * 1024L, // 5 GB
                        usedBytes = 5L * 1024L * 1024L * 1024L, // 5 GB used (FULL!)
                        status = "FULL"
                    ),
                    StorageBucket(
                        id = "bucket-4",
                        bucketName = "cloudflare-r2-tokyo-fail",
                        endpoint = "https://r2-fail.cloudflarestorage.com",
                        accessKeyId = "ak_tokyo_failed",
                        secretAccessKey = "sk_tokyo_failed",
                        totalQuotaBytes = 8L * 1024L * 1024L * 1024L, // 8 GB
                        usedBytes = 2L * 1024L * 1024L * 1024L, // 2 GB used
                        status = "DOWN" // Simulation down to trigger failover stream!
                    )
                )

                val mockFiles = mutableListOf(
                    CloudFile("file-1", "user-1", "tax_assessment_2025.pdf", "documents/tax_assessment_2025.pdf", 1450000L, "application/pdf", "bucket-1", true),
                    CloudFile("file-2", "user-1", "production_credentials.env", "documents/secure/production_credentials.env", 4500L, "application/octet-stream", "bucket-1", true),
                    CloudFile("file-3", "user-1", "summer_landscape_wallpaper.jpg", "photos/summer_landscape_wallpaper.jpg", 4800000L, "image/jpeg", "bucket-2", false),
                    CloudFile("file-4", "user-1", "core_analytics_charts.png", "photos/core_analytics_charts.png", 2300000L, "image/png", "bucket-2", false),
                    CloudFile("file-5", "user-1", "postgres_database_prod_backup.sql", "backups/postgres_database_prod_backup.sql", 35000000L, "application/sql", "bucket-2", true),
                    CloudFile("file-6", "user-1", "root_release_notes.md", "root_release_notes.md", 25000L, "text/markdown", "bucket-1", false)
                )

                _state.value = _state.value.copy(
                    isLoading = false,
                    buckets = mockBuckets,
                    allFiles = mockFiles
                )
                refreshCurrentFolderNode()
            } else {
                // Real DB execution
                val bucketsJob = storageRepository.fetchBuckets()
                val filesJob = storageRepository.fetchFiles()

                if (bucketsJob.isSuccess && filesJob.isSuccess) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        buckets = bucketsJob.getOrDefault(emptyList()),
                        allFiles = filesJob.getOrDefault(emptyList())
                    )
                    refreshCurrentFolderNode()
                } else {
                    val errMsg = "Buckets DB: ${bucketsJob.exceptionOrNull()?.message ?: "OK"}. Files DB: ${filesJob.exceptionOrNull()?.message ?: "OK"}"
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Failed to sync metadata. Please verify your Supabase Connection credentials in Secrets panel.\n\nError: $errMsg"
                    )
                }
            }
        }
    }

    // Smart Folder Navigation Core Parsing Code
    fun navigateTo(subfolder: String) {
        val nextPath = if (_state.value.currentPath.isEmpty()) {
            subfolder
        } else {
            "${_state.value.currentPath}/$subfolder"
        }
        _state.value = _state.value.copy(currentPath = nextPath)
        refreshCurrentFolderNode()
    }

    fun navigateBack() {
        val current = _state.value.currentPath
        if (current.isEmpty()) return
        
        val lastSlash = current.lastIndexOf('/')
        val parentPath = if (lastSlash == -1) "" else current.substring(0, lastSlash)
        _state.value = _state.value.copy(currentPath = parentPath)
        refreshCurrentFolderNode()
    }

    fun navigateToRoot() {
        _state.value = _state.value.copy(currentPath = "")
        refreshCurrentFolderNode()
    }

    private fun refreshCurrentFolderNode() {
        val currentPath = _state.value.currentPath
        val allFiles = _state.value.allFiles

        val folderFiles = mutableListOf<CloudFile>()
        val subFolders = mutableSetOf<String>()

        for (file in allFiles) {
            val fp = file.filePath
            if (currentPath.isEmpty()) {
                // Root level
                if (!fp.contains('/')) {
                    folderFiles.add(file)
                } else {
                    val rootDir = fp.substringBefore('/')
                    subFolders.add(rootDir)
                }
            } else {
                // Sub-folder level
                if (fp.startsWith("$currentPath/")) {
                    val relativePath = fp.removePrefix("$currentPath/")
                    if (!relativePath.contains('/')) {
                        folderFiles.add(file)
                    } else {
                        val subDirName = relativePath.substringBefore('/')
                        subFolders.add(subDirName)
                    }
                }
            }
        }

        _state.value = _state.value.copy(
            currentFolderFiles = folderFiles.sortedBy { it.filename },
            currentSubfolders = subFolders.sorted()
        )
    }

    // Real-Time Upload with Automatic Smart Pooling Failover Triggering
    fun startUpload(filename: String, mimeType: String, fileBytes: ByteArray, isEncrypted: Boolean) {
        val currentRelPath = _state.value.currentPath
        val filePath = if (currentRelPath.isEmpty()) filename else "$currentRelPath/$filename"

        _state.value = _state.value.copy(
            isUploading = true,
            uploadProgress = 0f,
            uploadBucketName = "Smart Router (Analyzing Pool...)",
            uploadFailovers = emptyList(),
            uploadStatusMessage = "Analyzing pooled capacities & status..."
        )

        viewModelScope.launch {
            if (isDemoMode) {
                // Aesthetic simulation behavior for full interactive demo demonstration
                // Simulating failover: let's pretend to contact "cloudflare-r2-tokyo-fail" (DOWN) first and retry on the first active one!
                delay(1200)
                _state.value = _state.value.copy(
                    uploadFailovers = listOf("cloudflare-r2-tokyo-fail"),
                    uploadBucketName = "cloudflare-r2-us-west",
                    uploadStatusMessage = "Failover Triggered! Re-routing byte stream to active 'cloudflare-r2-us-west' pool..."
                )
                delay(1000)

                // Simulated progress curve
                for (p in 1..10) {
                    delay(250)
                    _state.value = _state.value.copy(
                        uploadProgress = p / 10f,
                        uploadStatusMessage = "Streaming chunks to R2 Storage (${p * 10}%)..."
                    )
                }

                // Finalize insertion simulation
                val safeFileId = "sim-file-${UUID.randomUUID().toString().take(6)}"
                val newCloudFile = CloudFile(
                    id = safeFileId,
                    userId = "demo-user",
                    filename = filename,
                    filePath = filePath,
                    fileSize = fileBytes.size.toLong(),
                    mimeType = mimeType,
                    bucketId = "bucket-1", // us-west bucket
                    isEncrypted = isEncrypted
                )

                // Update simulated bucket sizes
                val updatedBuckets = _state.value.buckets.map { b ->
                    if (b.id == "bucket-1") {
                        b.copy(usedBytes = b.usedBytes + fileBytes.size)
                    } else b
                }

                _state.value = _state.value.copy(
                    isUploading = false,
                    uploadProgress = 0f,
                    buckets = updatedBuckets,
                    allFiles = _state.value.allFiles + newCloudFile,
                    uploadStatusMessage = null
                )
                refreshCurrentFolderNode()
            } else {
                // Real DB execution collects Flow stream securely
                storageRepository.uploadFile(
                    filename = filename,
                    filePath = filePath,
                    fileSize = fileBytes.size.toLong(),
                    mimeType = mimeType,
                    fileBytes = fileBytes,
                    isEncrypted = isEncrypted
                ).collect { status ->
                    when (status) {
                        is UploadStatus.Idle -> {
                            _state.value = _state.value.copy(
                                uploadStatusMessage = "Initializing direct binary chunk stream..."
                            )
                        }
                        is UploadStatus.Progress -> {
                            val activeBucket = status.currentBucketName
                            val failoverList = if (_state.value.uploadBucketName.isNotEmpty() && 
                                _state.value.uploadBucketName != "Smart Router (Analyzing Pool...)" && 
                                _state.value.uploadBucketName != activeBucket) {
                                // A change of active target bucket during progress indicates failover retrying occurred!
                                _state.value.uploadFailovers + _state.value.uploadBucketName
                            } else {
                                _state.value.uploadFailovers
                            }

                            _state.value = _state.value.copy(
                                uploadProgress = status.percentage,
                                uploadBucketName = activeBucket,
                                uploadFailovers = failoverList,
                                uploadStatusMessage = "Direct binary transfer to '$activeBucket' at ${(status.percentage * 100).toInt()}%"
                            )
                        }
                        is UploadStatus.Success -> {
                            // Reload files and buckets from network
                            _state.value = _state.value.copy(
                                isUploading = false,
                                uploadProgress = 0f,
                                uploadStatusMessage = null
                            )
                            loadStoragePool()
                        }
                        is UploadStatus.Error -> {
                            _state.value = _state.value.copy(
                                isUploading = false,
                                uploadProgress = 0f,
                                error = status.message,
                                uploadStatusMessage = null
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete file
    fun deleteFile(fileId: String, bucketId: String, filePath: String, fileSize: Long) {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            if (isDemoMode) {
                delay(500)
                // Filter file out
                val renewedFilesList = _state.value.allFiles.filter { it.id != fileId }
                // Deduct bucket capacity
                val renewedBucketsList = _state.value.buckets.map { b ->
                    if (b.id == bucketId) {
                        b.copy(usedBytes = (b.usedBytes - fileSize).coerceAtLeast(0))
                    } else b
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    buckets = renewedBucketsList,
                    allFiles = renewedFilesList
                )
                refreshCurrentFolderNode()
            } else {
                val deleteResult = storageRepository.deleteFile(fileId, bucketId, filePath)
                if (deleteResult.isSuccess) {
                    loadStoragePool()
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Failed to delete file: ${deleteResult.exceptionOrNull()?.message}"
                    )
                }
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
