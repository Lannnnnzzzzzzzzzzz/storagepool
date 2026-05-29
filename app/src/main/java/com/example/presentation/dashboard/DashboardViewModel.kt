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
                    val rawBuckets = bucketsJob.getOrDefault(emptyList())
                    val files = filesJob.getOrDefault(emptyList())

                    // Reconcile storage capacity: calculate actual used space from file metadata
                    val reconciledBuckets = rawBuckets.map { b ->
                        val calculatedUsedBytes = files.filter { it.bucketId == b.id }.sumOf { it.fileSize }
                        b.copy(usedBytes = calculatedUsedBytes)
                    }

                    _state.value = _state.value.copy(
                        isLoading = false,
                        buckets = reconciledBuckets,
                        allFiles = files
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

    fun addBucket(
        bucketName: String,
        endpoint: String,
        accessKeyId: String,
        secretAccessKey: String,
        totalQuotaBytes: Long,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isDemoMode) {
            val newBucket = StorageBucket(
                id = "mock-" + UUID.randomUUID().toString().take(6),
                bucketName = bucketName.trim(),
                endpoint = endpoint.trim(),
                accessKeyId = accessKeyId.trim(),
                secretAccessKey = secretAccessKey.trim(),
                totalQuotaBytes = totalQuotaBytes,
                usedBytes = 0,
                status = "ACTIVE"
            )
            _state.value = _state.value.copy(
                buckets = _state.value.buckets + newBucket
            )
            onSuccess()
            return
        }
        
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            val result = storageRepository.addBucket(
                bucketName = bucketName,
                endpoint = endpoint,
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                totalQuotaBytes = totalQuotaBytes
            )
            _state.value = _state.value.copy(isLoading = false)
            if (result.isSuccess) {
                loadStoragePool()
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "Gagal mendaftarkan Node bucket fisik")
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
                    if (file.filename != ".keep") {
                        folderFiles.add(file)
                    }
                } else {
                    val rootDir = fp.substringBefore('/')
                    subFolders.add(rootDir)
                }
            } else {
                // Sub-folder level
                if (fp.startsWith("$currentPath/")) {
                    val relativePath = fp.removePrefix("$currentPath/")
                    if (!relativePath.contains('/')) {
                        if (file.filename != ".keep") {
                            folderFiles.add(file)
                        }
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

    // Dynamic virtual directory creation using 0-byte placeholder kept files
    fun createFolder(folderName: String) {
        val cleanFolderName = folderName.trim().removeSuffix("/")
        if (cleanFolderName.isEmpty()) return

        val currentRelPath = _state.value.currentPath
        val folderPath = if (currentRelPath.isEmpty()) cleanFolderName else "$currentRelPath/$cleanFolderName"
        val fullPath = "$folderPath/.keep"

        _state.value = _state.value.copy(
            isUploading = true,
            uploadProgress = 0f,
            uploadBucketName = "Smart Router (Analyzing Pool...)",
            uploadFailovers = emptyList(),
            uploadStatusMessage = "Creating directory placeholder..."
        )

        viewModelScope.launch {
            if (isDemoMode) {
                delay(800)
                val safeFileId = "sim-folder-${UUID.randomUUID().toString().take(6)}"
                val newCloudFile = CloudFile(
                    id = safeFileId,
                    userId = "demo-user",
                    filename = ".keep",
                    filePath = fullPath,
                    fileSize = 0L,
                    mimeType = "application/octet-stream",
                    bucketId = "bucket-1",
                    isEncrypted = false
                )
                _state.value = _state.value.copy(
                    isUploading = false,
                    allFiles = _state.value.allFiles + newCloudFile,
                    uploadStatusMessage = null
                )
                refreshCurrentFolderNode()
            } else {
                try {
                    storageRepository.uploadFile(
                        filename = ".keep",
                        filePath = fullPath,
                        fileSize = 0L,
                        mimeType = "application/octet-stream",
                        fileBytes = ByteArray(0),
                        isEncrypted = false
                    ).collect { status ->
                        when (status) {
                            is UploadStatus.Idle -> {
                                _state.value = _state.value.copy(
                                    uploadStatusMessage = "Initializing directory placeholder..."
                                )
                            }
                            is UploadStatus.Progress -> {
                                _state.value = _state.value.copy(
                                    uploadStatusMessage = "Writing directory marker in ${status.currentBucketName}..."
                                )
                            }
                            is UploadStatus.Success -> {
                                _state.value = _state.value.copy(
                                    isUploading = false,
                                    uploadStatusMessage = null
                                )
                                loadStoragePool()
                            }
                            is UploadStatus.Error -> {
                                _state.value = _state.value.copy(
                                    isUploading = false,
                                    error = status.message,
                                    uploadStatusMessage = null
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    _state.value = _state.value.copy(
                        isUploading = false,
                        error = e.message ?: "Gagal membuat folder",
                        uploadStatusMessage = null
                    )
                }
            }
        }
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
                try {
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
                } catch (e: Exception) {
                    _state.value = _state.value.copy(
                        isUploading = false,
                        uploadProgress = 0f,
                        error = e.message ?: "Unexpected upload error occurred",
                        uploadStatusMessage = null
                    )
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

    // Direct local download of decrypted storage pool content
    fun downloadFileToUri(
        file: CloudFile,
        context: android.content.Context,
        targetUri: android.net.Uri,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            if (isDemoMode) {
                delay(1200)
                try {
                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        val sampleContent = "Simulated File Download:\nName : ${file.filename}\nEncrypted : ${file.isEncrypted}\nSize : ${file.fileSize} bytes"
                        out.write(sampleContent.toByteArray())
                    }
                    _state.value = _state.value.copy(isLoading = false)
                    onSuccess("File '${file.filename}' berhasil diunduh via Demo-Simulasi!")
                } catch (e: Exception) {
                    _state.value = _state.value.copy(isLoading = false)
                    onError("Demo download error: ${e.message}")
                }
                return@launch
            }

            val result = storageRepository.downloadFile(file)
            _state.value = _state.value.copy(isLoading = false)
            if (result.isSuccess) {
                val bytes = result.getOrThrow()
                try {
                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(bytes)
                    }
                    onSuccess("Berhasil mengunduh '${file.filename}'")
                } catch (e: Exception) {
                    onError("Gagal menyimpan file ke lokasi: ${e.message}")
                }
            } else {
                onError("Gagal mengunduh file: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    // Generate secure 7-day presigned direct S3/R2 sharing link
    fun generateShareUrl(
        file: CloudFile,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            if (isDemoMode) {
                delay(800)
                _state.value = _state.value.copy(isLoading = false)
                onSuccess("https://demo-node-pool.r2.cloudflarestorage.com/${file.bucketId}/${file.filePath}?X-Amz-Signature=demo-sig-123")
                return@launch
            }

            val result = storageRepository.generateShareUrl(file)
            _state.value = _state.value.copy(isLoading = false)
            if (result.isSuccess) {
                onSuccess(result.getOrThrow())
            } else {
                onError("Gagal membuat link berbagi: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    // Direct decrypted File Attachment sharing via System Share sheet
    fun shareDecryptedFileDirectly(
        file: CloudFile,
        context: android.content.Context,
        onSuccess: (android.net.Uri, String) -> Unit,
        onError: (String) -> Unit
    ) {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            if (isDemoMode) {
                delay(1200)
                try {
                    val tempDir = java.io.File(context.cacheDir, "shared_temp")
                    if (!tempDir.exists()) tempDir.mkdirs()
                    val cacheFile = java.io.File(tempDir, file.filename)
                    cacheFile.writeText("Simulated Decrypted File Content for '${file.filename}'")
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "com.example.fileprovider",
                        cacheFile
                    )
                    _state.value = _state.value.copy(isLoading = false)
                    onSuccess(uri, file.mimeType)
                } catch (e: Exception) {
                    _state.value = _state.value.copy(isLoading = false)
                    onError("Demo sharing error: ${e.message}")
                }
                return@launch
            }

            val result = storageRepository.downloadFile(file)
            _state.value = _state.value.copy(isLoading = false)
            if (result.isSuccess) {
                val bytes = result.getOrThrow()
                try {
                    val cacheDirFile = java.io.File(context.cacheDir, "shared_temp")
                    if (!cacheDirFile.exists()) {
                        cacheDirFile.mkdirs()
                    }
                    val tempFile = java.io.File(cacheDirFile, file.filename)
                    tempFile.writeBytes(bytes)

                    val shareUri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "com.example.fileprovider",
                        tempFile
                    )
                    onSuccess(shareUri, file.mimeType)
                } catch (e: Exception) {
                    onError("Gagal memproses file untuk dibagikan: ${e.message}")
                }
            } else {
                onError("Gagal mengunduh file untuk dibagikan: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    // Unduh, dekripsi, simpan ke berkas sementara & langsung buka lewat Intent ACTION_VIEW
    fun downloadAndOpenFile(
        file: CloudFile,
        context: android.content.Context,
        onSuccess: (android.net.Uri, String) -> Unit,
        onError: (String) -> Unit
    ) {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            if (isDemoMode) {
                delay(1200)
                try {
                    val tempDir = java.io.File(context.cacheDir, "shared_temp")
                    if (!tempDir.exists()) tempDir.mkdirs()
                    val cacheFile = java.io.File(tempDir, file.filename)
                    if (file.mimeType.startsWith("image/")) {
                        cacheFile.writeText("Simulated Decrypted Image Content")
                    } else {
                        cacheFile.writeText("Simulated Decrypted file content.")
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "com.example.fileprovider",
                        cacheFile
                    )
                    _state.value = _state.value.copy(isLoading = false)
                    onSuccess(uri, file.mimeType)
                } catch (e: Exception) {
                    _state.value = _state.value.copy(isLoading = false)
                    onError("Demo view error: ${e.message}")
                }
                return@launch
            }

            val result = storageRepository.downloadFile(file)
            _state.value = _state.value.copy(isLoading = false)
            if (result.isSuccess) {
                val bytes = result.getOrThrow()
                try {
                    val tempDir = java.io.File(context.cacheDir, "shared_temp")
                    if (!tempDir.exists()) tempDir.mkdirs()
                    val tempFile = java.io.File(tempDir, file.filename)
                    tempFile.writeBytes(bytes)

                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "com.example.fileprovider",
                        tempFile
                    )
                    onSuccess(uri, file.mimeType)
                } catch (e: Exception) {
                    onError("Gagal menyiapkan penampil berkas: ${e.message}")
                }
            } else {
                onError("Gagal mengunduh berkas: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    // Simpan gambar/video ke Galeri Publik (MediaStore) agar langsung terdeteksi di galeri HP/emulator
    fun saveToGallery(
        file: CloudFile,
        context: android.content.Context,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!file.mimeType.startsWith("image/") && !file.mimeType.startsWith("video/")) {
            onError("Bukan berkas media (foto/video). Gunakan opsi 'Simpan File (Unduh)' untuk berkas ini.")
            return
        }

        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            if (isDemoMode) {
                delay(1200)
                _state.value = _state.value.copy(isLoading = false)
                onSuccess("Foto '${file.filename}' berhasil disimpan ke Galeri!")
                return@launch
            }

            val result = storageRepository.downloadFile(file)
            _state.value = _state.value.copy(isLoading = false)
            if (result.isSuccess) {
                val bytes = result.getOrThrow()
                try {
                    val resolver = context.contentResolver
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.filename)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, file.mimeType)
                        if (file.mimeType.startsWith("image/")) {
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/StoragePool")
                        } else {
                            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/StoragePool")
                        }
                    }

                    val baseUri = if (file.mimeType.startsWith("image/")) {
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    } else {
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }

                    val uri = resolver.insert(baseUri, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            out.write(bytes)
                        }
                        onSuccess("Berhasil menyimpan '${file.filename}' ke Galeri Foto!")
                    } else {
                        onError("Gagal membuat entri MediaStore.")
                    }
                } catch (e: Exception) {
                    onError("Gagal memproses berkas media: ${e.message}")
                }
            } else {
                onError("Gagal mengunduh berkas: ${result.exceptionOrNull()?.message}")
            }
        }
    }
}
