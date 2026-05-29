package com.example.presentation.dashboard

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.CloudFile
import com.example.domain.model.StorageBucket
import com.example.ui.theme.*
import java.util.*
import androidx.compose.ui.text.style.TextAlign

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    isDemoMode: Boolean,
    onSignOut: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var showUploadConfigDialog by remember { mutableStateOf(false) }
    var showRegisterBucketDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var pickedFileUri by remember { mutableStateOf<Uri?>(null) }
    var pickedFileName by remember { mutableStateOf("") }
    var pickedFileSize by remember { mutableStateOf(0L) }
    var pickedFileType by remember { mutableStateOf("") }
    var encryptClientSide by remember { mutableStateOf(false) }

    // Launcher contract for Document Picker
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pickedFileUri = uri
            val meta = getUriMetaData(context, uri)
            pickedFileName = meta.first
            pickedFileSize = meta.second
            pickedFileType = context.contentResolver.getType(uri) ?: meta.third
            encryptClientSide = false
            showUploadConfigDialog = true
        }
    }

    var selectedFileForActions by remember { mutableStateOf<CloudFile?>(null) }
    var activeFileForDownload by remember { mutableStateOf<CloudFile?>(null) }

    val fileSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val fileToDownload = activeFileForDownload
        if (uri != null && fileToDownload != null) {
            viewModel.downloadFileToUri(
                file = fileToDownload,
                context = context,
                targetUri = uri,
                onSuccess = { msg ->
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                },
                onError = { err ->
                    android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                }
            )
        }
        activeFileForDownload = null
    }

    LaunchedEffect(Unit) {
        viewModel.setDemoMode(isDemoMode)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = "Cloud Logo",
                            tint = ElectricCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "StoragePool",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            ),
                            color = PureWhite
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadStoragePool() },
                        modifier = Modifier.testTag("refresh_pool_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Pools",
                            tint = ElectricCyan
                        )
                    }
                    IconButton(
                        onClick = onSignOut,
                        modifier = Modifier.testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = "Sign Out",
                            tint = NeonPink
                        )
                    }
                },
                navigationIcon = {
                    if (isDemoMode) {
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .background(CyberTeal.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .border(1.dp, CyberTeal.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "DEMO",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = CyberTeal
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = DeepGreySurface,
                    titleContentColor = PureWhite
                )
            )
        },
        floatingActionButton = {
            if (!state.isUploading) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 16.dp, end = 8.dp)
                ) {
                    // Create Folder Button
                    ExtendedFloatingActionButton(
                        onClick = { showCreateFolderDialog = true },
                        modifier = Modifier.testTag("floating_create_folder_button"),
                        containerColor = DeepGreySurface,
                        contentColor = ElectricCyan,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = "Buat Folder",
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = {
                            Text(
                                text = "Buat Folder",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    )

                    // Upload File Button
                    ExtendedFloatingActionButton(
                        onClick = { documentPickerLauncher.launch("*/*") },
                        modifier = Modifier.testTag("floating_upload_picker_button"),
                        containerColor = ElectricCyan,
                        contentColor = Color(0xFF381E72),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = "Import Binary File",
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        text = {
                            Text(
                                text = "Upload File",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ObsidianBg),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 850.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(12.dp)) }

                // 1. Unified Aggregate Storage Pool Progress Bar Card
                item {
                    AggregatePoolStatsCard(state = state)
                }

                // 2. Expandable Active Pools (Individual physical R2 buckets configuration)
                item {
                    BucketsAllocationPanel(
                        state = state,
                        onRegisterBucket = { showRegisterBucketDialog = true }
                    )
                }

                // 3. Folder Breadcrumb Navigation row
                item {
                    FolderBreadcrumbRow(
                        currentPath = state.currentPath,
                        onNavigateRoot = { viewModel.navigateToRoot() },
                        onNavigateBack = { viewModel.navigateBack() }
                    )
                }

                // 4. Folder Content View (Virtual Subfolders list with adaptive chunked row layout to prevent double-scroll list nesting)
                if (state.currentSubfolders.isNotEmpty()) {
                    item {
                        Text(
                            text = "DIRECTORIES",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            ),
                            color = TextMuted,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        )
                    }

                    val columns = 2
                    val folderChunks = state.currentSubfolders.chunked(columns)
                    items(folderChunks) { chunk ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            chunk.forEach { folder ->
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(CardGreySurface, RoundedCornerShape(10.dp))
                                        .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                                        .clickable { viewModel.navigateTo(folder) }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = "Virtual Directory Folder",
                                        tint = CyberTeal,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = folder,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = PureWhite,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (chunk.size < columns) {
                                repeat(columns - chunk.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "S3 ACTIVE FILES",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            ),
                            color = TextMuted
                        )
                        Text(
                            text = "${state.currentFolderFiles.size} items",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                // File items list
                if (state.isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp), color = ElectricCyan)
                        }
                    }
                } else if (state.currentFolderFiles.isEmpty() && state.currentSubfolders.isEmpty()) {
                    item {
                        EmptyPoolState()
                    }
                } else {
                    items(state.currentFolderFiles) { file ->
                        val targetBucketName = state.buckets.find { it.id == file.bucketId }?.bucketName ?: "Unknown Bucket"
                        FileItemCard(
                            file = file,
                            allocatedBucketName = targetBucketName,
                            onClick = { selectedFileForActions = file },
                            onDelete = {
                                viewModel.deleteFile(file.id, file.bucketId, file.filePath, file.fileSize)
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) } // Spacer for bottom overlay and FAB bounds
            }

            // Real-Time Upload Transmitting Overlay panel (With active progress, target, and automated failover alert)
            AnimatedVisibility(
                visible = state.isUploading,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                UploadProgressOverlay(state = state)
            }

            // Exception/Error Alert Dialog integration
            if (state.error != null) {
                val errorText = state.error ?: ""
                val isJwtExpired = errorText.contains("JWT expired", ignoreCase = true) || errorText.contains("PGRST303", ignoreCase = true)

                AlertDialog(
                    onDismissRequest = { viewModel.clearError() },
                    title = { Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = "Error Logo", tint = NeonPink)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isJwtExpired) "Sesi Autentikasi Berakhir" else "System Synchronization Notice", color = PureWhite)
                    }},
                    text = { 
                        Text(
                            text = if (isJwtExpired) {
                                "Koneksi ke cluster database Supabase terputus karena token sesi login Anda telah kedaluwarsa (JWT expired).\n\n" +
                                "Sesi login Supabase secara default berakhir dalam 1 jam demi alasan keamanan. Silakan klik 'Keluar & Hubungkan Ulang' di bawah ini untuk masuk kembali dan memperbarui sesi Anda."
                            } else {
                                errorText
                            },
                            color = TextPrimary
                        )
                    },
                    confirmButton = {
                        if (isJwtExpired) {
                            Button(
                                onClick = {
                                    viewModel.clearError()
                                    onSignOut()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                                modifier = Modifier.testTag("sign_out_expired_button")
                            ) {
                                Text("Keluar & Hubungkan Ulang", color = Color(0xFF381E72), fontWeight = FontWeight.Bold)
                            }
                        } else {
                            TextButton(
                                onClick = { viewModel.clearError() },
                                modifier = Modifier.testTag("dismiss_error_button")
                            ) {
                                Text("Dismiss", color = ElectricCyan)
                            }
                        }
                    },
                    dismissButton = if (isJwtExpired) {
                        {
                            TextButton(
                                onClick = { viewModel.clearError() }
                            ) {
                                Text("Batal", color = TextMuted)
                            }
                        }
                    } else null,
                    containerColor = DeepGreySurface,
                    tonalElevation = 6.dp
                )
            }

            // Metadata upload confirmation and AES toggles Dialog
            if (showUploadConfigDialog) {
                UploadConfigDialog(
                    fileName = pickedFileName,
                    fileSize = pickedFileSize,
                    fileExtension = MimeTypeMap.getFileExtensionFromUrl(pickedFileName),
                    encryptClientSide = encryptClientSide,
                    onToggleEncryption = { encryptClientSide = it },
                    onCancel = {
                        showUploadConfigDialog = false
                        pickedFileUri = null
                    },
                    onConfirm = { customName ->
                        showUploadConfigDialog = false
                        val uri = pickedFileUri
                        if (uri != null) {
                            val bytes = readUriBytes(context, uri)
                            if (bytes != null) {
                                viewModel.startUpload(
                                    filename = customName,
                                    mimeType = pickedFileType,
                                    fileBytes = bytes,
                                    isEncrypted = encryptClientSide
                                )
                            } else {
                                Log.e("Dashboard", "Failed to load input stream bytes from physical document URI")
                            }
                        }
                    }
                )
            }

            if (showCreateFolderDialog) {
                AlertDialog(
                    onDismissRequest = { 
                        showCreateFolderDialog = false
                        newFolderName = ""
                    },
                    containerColor = DeepGreySurface,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = "New Folder",
                                tint = ElectricCyan,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Buat Folder Baru",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    color = PureWhite,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    },
                    text = {
                        Column {
                            Text(
                                text = "Masukkan nama folder yang ingin dibuat. Folder ini akan dibuat secara virtual di dalam directory aktif.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            OutlinedTextField(
                                value = newFolderName,
                                onValueChange = { newFolderName = it },
                                label = { Text("Nama Folder") },
                                placeholder = { Text("Contoh: foto-kontrak") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("folder_name_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite,
                                    focusedBorderColor = ElectricCyan,
                                    unfocusedBorderColor = Color.Gray,
                                    focusedLabelColor = ElectricCyan,
                                    unfocusedLabelColor = Color.Gray
                                )
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (newFolderName.isNotBlank()) {
                                    viewModel.createFolder(newFolderName)
                                    showCreateFolderDialog = false
                                    newFolderName = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                            modifier = Modifier.testTag("confirm_create_folder_button")
                        ) {
                            Text("Buat", color = Color(0xFF381E72), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showCreateFolderDialog = false
                                newFolderName = ""
                            }
                        ) {
                            Text("Batal", color = TextMuted)
                        }
                    }
                )
            }

            selectedFileForActions?.let { file ->
                val targetBucketName = state.buckets.find { it.id == file.bucketId }?.bucketName ?: "Unknown Bucket"
                AlertDialog(
                    onDismissRequest = { selectedFileForActions = null },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = getMimeTypeIcon(file.mimeType),
                                contentDescription = "Tipe File Icon",
                                tint = ElectricCyan,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Transmisi & Kelola File",
                                color = PureWhite,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = file.filename,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = PureWhite,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Divider(color = BorderDark, modifier = Modifier.padding(vertical = 4.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Ukuran:", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                                Text(formatFileSize(file.fileSize), color = PureWhite, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Tipe Mime:", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                                Text(file.mimeType, color = PureWhite, style = MaterialTheme.typography.bodyMedium)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Target Node Pool:", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                                Text(targetBucketName, color = CyberTeal, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Enkripsi Klien AES:", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (file.isEncrypted) {
                                        Icon(
                                            imageVector = Icons.Default.VerifiedUser,
                                            contentDescription = "Encrypted On",
                                            tint = ElectricCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Klien Aktif", color = ElectricCyan, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    } else {
                                        Text("Tidak Aktif", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Opsi Transmisi & Bagikan:", color = ElectricCyan, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))

                            // 1. Unduh & Buka Berkas Langsung
                            Button(
                                onClick = {
                                    viewModel.downloadAndOpenFile(
                                        file = file,
                                        context = context,
                                        onSuccess = { uri, mime ->
                                            try {
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, mime)
                                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(context, "Tidak ada aplikasi untuk membuka file ini. Silakan unduh pembaca file bersesuaian.", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        },
                                        onError = { err ->
                                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    )
                                    selectedFileForActions = null
                                },
                                modifier = Modifier.fillMaxWidth().testTag("dialog_download_open_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan.copy(alpha = 0.25f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Visibility,
                                        contentDescription = "Buka File Langsung",
                                        tint = ElectricCyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text("Unduh & Buka Berkas", color = PureWhite, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                        Text("Dekripsi, unduh otomatis, dan lihat kontennya langsung", color = TextMuted, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                                    }
                                }
                            }

                            // 2. Simpan ke Galeri Foto (Pictures / Movies) — only for photos & videos
                            if (file.mimeType.startsWith("image/") || file.mimeType.startsWith("video/")) {
                                Button(
                                    onClick = {
                                        viewModel.saveToGallery(
                                            file = file,
                                            context = context,
                                            onSuccess = { msg ->
                                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                            },
                                            onError = { err ->
                                                android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        )
                                        selectedFileForActions = null
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("dialog_save_to_gallery_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberTeal.copy(alpha = 0.25f)),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Image,
                                            contentDescription = "Simpan ke Galeri",
                                            tint = CyberTeal,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(horizontalAlignment = Alignment.Start) {
                                            Text("Simpan ke Galeri Foto", color = PureWhite, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                            Text("Dekripsi & ekspor gambar/video ini agar muncul di Galeri HP", color = TextMuted, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                                        }
                                    }
                                }
                            }

                            // 3. Download decrypted file via Document Picker (SAF)
                            Button(
                                onClick = {
                                    activeFileForDownload = file
                                    fileSaveLauncher.launch(file.filename)
                                    selectedFileForActions = null
                                },
                                modifier = Modifier.fillMaxWidth().testTag("dialog_download_decrypted_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberTeal.copy(alpha = 0.12f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "Simpan Manual",
                                        tint = TextMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text("Ekspor File Manual", color = PureWhite, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                        Text("Pilih sendiri lokasi penyimpanan folder untuk berkas ini", color = TextMuted, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                                    }
                                }
                            }

                            // 4. Share downloaded decrypted actual attachment file content
                            Button(
                                onClick = {
                                    viewModel.shareDecryptedFileDirectly(
                                        file = file,
                                        context = context,
                                        onSuccess = { uri, mime ->
                                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = mime
                                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(android.content.Intent.createChooser(shareIntent, "Bagikan Berkas '${file.filename}'"))
                                        },
                                        onError = { err ->
                                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    )
                                    selectedFileForActions = null
                                },
                                modifier = Modifier.fillMaxWidth().testTag("dialog_share_file_content_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan.copy(alpha = 0.15f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Bagikan File Sebenarnya",
                                        tint = ElectricCyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text("Bagikan Konten Berkas (WhatsApp/Email)", color = PureWhite, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                        Text("Kirim berkas versi UN-ENCRYPTED langsung ke teman Anda", color = TextMuted, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                                    }
                                }
                            }

                            // 5. Share temporary link
                            Button(
                                onClick = {
                                    viewModel.generateShareUrl(
                                        file = file,
                                        onSuccess = { url ->
                                            // Copy link to clipboard
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("Presigned Sharing Link", url)
                                            clipboard.setPrimaryClip(clip)
                                            android.widget.Toast.makeText(context, "Link disalin! Berlaku selama 7 hari.", android.widget.Toast.LENGTH_LONG).show()

                                            // Open Share Dialog
                                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(android.content.Intent.EXTRA_TEXT, "Halo! Berikut adalah tautan aman untuk mengunduh berkas '${file.filename}' dari StoragePool (aktif selama 7 hari):\n\n$url")
                                            }
                                            context.startActivity(android.content.Intent.createChooser(intent, "Bagikan Link Unduh"))
                                        },
                                        onError = { err ->
                                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    )
                                    selectedFileForActions = null
                                },
                                modifier = Modifier.fillMaxWidth().testTag("dialog_share_link_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberTeal.copy(alpha = 0.15f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Salin Tautan",
                                        tint = CyberTeal,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text("Bagikan Tautan Unduh Langsung (7 Hari)", color = PureWhite, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                        Text("Buat link awan S3/R2 terenkripsi (sangat aman, berlaku 7 hari)", color = TextMuted, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { selectedFileForActions = null }
                        ) {
                            Text("Tutup", color = ElectricCyan, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteFile(file.id, file.bucketId, file.filePath, file.fileSize)
                                selectedFileForActions = null
                            }
                        ) {
                            Text("Hapus Berkas", color = NeonPink, fontWeight = FontWeight.Bold)
                        }
                    },
                    containerColor = DeepGreySurface,
                    tonalElevation = 6.dp
                )
            }

            if (showRegisterBucketDialog) {
                var bucketNameInput by remember { mutableStateOf("") }
                var endpointInput by remember { mutableStateOf("") }
                var accessKeyIdInput by remember { mutableStateOf("") }
                var secretAccessKeyInput by remember { mutableStateOf("") }
                var sizeGbInput by remember { mutableStateOf("10") }
                var registerError by remember { mutableStateOf<String?>(null) }
                var isRegistering by remember { mutableStateOf(false) }

                AlertDialog(
                    onDismissRequest = { 
                        if (!isRegistering) showRegisterBucketDialog = false 
                    },
                    title = {
                        Text(
                            text = "Register R2 Storage Node",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = ElectricCyan
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Aggregates this physical endpoint to your virtual storage pools cluster dynamically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )

                            OutlinedTextField(
                                value = bucketNameInput,
                                onValueChange = { bucketNameInput = it },
                                label = { Text("Bucket Name") },
                                placeholder = { Text("e.g. storage-pool-west") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberTeal,
                                    unfocusedBorderColor = BorderDark,
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("bucket_name_node_input")
                            )

                            OutlinedTextField(
                                value = endpointInput,
                                onValueChange = { endpointInput = it },
                                label = { Text("Endpoint URL") },
                                placeholder = { Text("https://<account-id>.r2.cloudflarestorage.com") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberTeal,
                                    unfocusedBorderColor = BorderDark,
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("endpoint_node_input")
                            )

                            OutlinedTextField(
                                value = accessKeyIdInput,
                                onValueChange = { accessKeyIdInput = it },
                                label = { Text("Access Key ID") },
                                placeholder = { Text("S3 Access Key") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberTeal,
                                    unfocusedBorderColor = BorderDark,
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("access_key_node_input")
                            )

                            OutlinedTextField(
                                value = secretAccessKeyInput,
                                onValueChange = { secretAccessKeyInput = it },
                                label = { Text("Secret Access Key") },
                                placeholder = { Text("S3 Secret Key") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberTeal,
                                    unfocusedBorderColor = BorderDark,
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("secret_key_node_input")
                            )

                            OutlinedTextField(
                                value = sizeGbInput,
                                onValueChange = { sizeGbInput = it },
                                label = { Text("Total Capacity (GB)") },
                                placeholder = { Text("10") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberTeal,
                                    unfocusedBorderColor = BorderDark,
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("capacity_node_input")
                            )

                            if (registerError != null) {
                                Text(
                                    text = registerError!!,
                                    color = NeonPink,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (bucketNameInput.isBlank() || endpointInput.isBlank() || 
                                    accessKeyIdInput.isBlank() || secretAccessKeyInput.isBlank()
                                ) {
                                    registerError = "All credential fields are required."
                                    return@Button
                                }
                                val sizeGb = sizeGbInput.toLongOrNull() ?: 10L
                                if (sizeGb <= 0) {
                                    registerError = "Quota must be a positive integer."
                                    return@Button
                                }

                                isRegistering = true
                                registerError = null
                                
                                viewModel.addBucket(
                                    bucketName = bucketNameInput.trim(),
                                    endpoint = endpointInput.trim(),
                                    accessKeyId = accessKeyIdInput.trim(),
                                    secretAccessKey = secretAccessKeyInput.trim(),
                                    totalQuotaBytes = sizeGb * 1024L * 1024L * 1024L,
                                    onSuccess = {
                                        isRegistering = false
                                        showRegisterBucketDialog = false
                                    },
                                    onError = { err ->
                                        isRegistering = false
                                        registerError = err
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                            modifier = Modifier.testTag("dialog_confirm_register_bucket")
                        ) {
                            if (isRegistering) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = DeepGreySurface)
                            } else {
                                Text("Add Node", color = DeepGreySurface, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showRegisterBucketDialog = false },
                            enabled = !isRegistering
                        ) {
                            Text("Cancel", color = TextMuted)
                        }
                    },
                    containerColor = DeepGreySurface
                )
            }
        }
    }
}

// === Sub-components ===

@Composable
fun AggregatePoolStatsCard(state: DashboardState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderDark, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = DeepGreySurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TOTAL AGGREGATE POOL",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = TextMuted
                    )
                    Text(
                        text = "${formatFileSize(state.usedSpaceBytes)} / ${formatFileSize(state.totalCapacityBytes)}",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = PureWhite,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .background(ElectricCyan.copy(alpha = 0.12f), CircleShape)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "${(state.aggregatePercentage * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = ElectricCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = state.aggregatePercentage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .testTag("aggregate_capacity_progress_bar"),
                color = ElectricCyan,
                trackColor = BorderDark
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${state.buckets.filter { it.status == "ACTIVE" }.size} Active R2",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                Text(
                    text = "${state.allFiles.size} Objects Synced",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = CyberTeal
                )
            }
        }
    }
}

@Composable
fun BucketsAllocationPanel(state: DashboardState, onRegisterBucket: () -> Unit) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DeepGreySurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Expand Tab Trigger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = "Databases Connector",
                        tint = CyberTeal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Physical Storage Nodes Pool (${state.buckets.size})",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = PureWhite
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand Nodes",
                    tint = TextMuted
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Divider(color = BorderDark, modifier = Modifier.padding(bottom = 6.dp))

                    for (bucket in state.buckets) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CardGreySurface, RoundedCornerShape(8.dp))
                                .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = bucket.bucketName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = PureWhite
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    BucketStatusBadge(status = bucket.status)
                                }
                                Text(
                                    text = "Direct Endpoint: ${bucket.endpoint.substringAfter("://").substringBefore("/")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = bucket.usedPercentage,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape),
                                    color = if (bucket.status == "FULL") NeonPink else CyberTeal,
                                    trackColor = BorderDark
                                )
                                Text(
                                    text = "Connector usage: ${formatFileSize(bucket.usedBytes)} of ${formatFileSize(bucket.totalQuotaBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onRegisterBucket,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberTeal.copy(alpha = 0.12f),
                            contentColor = CyberTeal
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CyberTeal.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .testTag("register_bucket_btn"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add node",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Register S3 R2 Storage Node",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BucketStatusBadge(status: String) {
    val (color, label) = when (status.uppercase()) {
        "ACTIVE" -> CyberTeal to "ACTIVE"
        "FULL" -> AmberWarning to "FULL"
        else -> NeonPink to "DOWN"
    }

    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color
        )
    }
}

@Composable
fun FolderBreadcrumbRow(
    currentPath: String,
    onNavigateRoot: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .background(DeepGreySurface, RoundedCornerShape(8.dp))
                .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Active Cluster Dir",
                tint = CyberTeal,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onNavigateRoot() }
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Root",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = if (currentPath.isEmpty()) ElectricCyan else PureWhite,
                modifier = Modifier.clickable { onNavigateRoot() }
            )

            if (currentPath.isNotEmpty()) {
                val segments = currentPath.split('/')
                segments.forEach { segment ->
                    Text(
                        text = " / ",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = segment,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (currentPath.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(DeepGreySurface, RoundedCornerShape(8.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    .clickable(onClick = onNavigateBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Default.ArrowBack,
                    contentDescription = "Up directory level",
                    tint = PureWhite,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItemCard(
    file: CloudFile,
    allocatedBucketName: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .testTag("file_item_card_${file.id}"),
        colors = CardDefaults.cardColors(containerColor = CardGreySurface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(DeepGreySurface, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getMimeTypeIcon(file.mimeType),
                        contentDescription = "file mime icon",
                        tint = if (file.isEncrypted) ElectricCyan else TextMuted
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = file.filename,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = PureWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (file.isEncrypted) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = "AES Client Side Encrypted File Record",
                                tint = ElectricCyan,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        text = "${formatFileSize(file.fileSize)}  •  ${file.mimeType.substringBefore("/")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = "Routed Node Pool: $allocatedBucketName",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        color = CyberTeal,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_file_button_${file.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Sweep Object from Cloud",
                    tint = NeonPink.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun UploadProgressOverlay(state: DashboardState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianBg)
            .border(
                1.dp,
                if (state.uploadFailovers.isNotEmpty()) AmberWarning.copy(alpha = 0.5f) else BorderDark,
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = DeepGreySurface),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = ElectricCyan)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Deploying stream chunk...",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = PureWhite
                    )
                }
                Text(
                    text = "${(state.uploadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = ElectricCyan
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = state.uploadProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = ElectricCyan,
                trackColor = BorderDark
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = "Target pool container",
                    tint = CyberTeal,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Streaming to container: '${state.uploadBucketName}'",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }

            // failover UI triggers: When failover lists are active!
            if (state.uploadFailovers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AmberWarning.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .border(1.dp, AmberWarning.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = "Failover Warn",
                        tint = AmberWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "SMART ROUTING AUTOMATIC RETRY",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = AmberWarning
                        )
                        Text(
                            text = "Target bucket down or full: re-routed to '${state.uploadBucketName}'",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadConfigDialog(
    fileName: String,
    fileSize: Long,
    fileExtension: String,
    encryptClientSide: Boolean,
    onToggleEncryption: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var editingName by remember { mutableStateOf(fileName) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.UploadFile, contentDescription = "Upload settings", tint = ElectricCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Compile Pool Upload", color = PureWhite)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Prepare file for Cloudflare R2 multi-pool distribution.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = editingName,
                    onValueChange = { editingName = it },
                    label = { Text("Set Cloud Path Name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("upload_custom_name_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = BorderDark,
                        focusedLabelColor = ElectricCyan,
                        unfocusedLabelColor = TextMuted,
                        cursorColor = ElectricCyan,
                        focusedContainerColor = DeepGreySurface,
                        unfocusedContainerColor = DeepGreySurface
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardGreySurface, RoundedCornerShape(10.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.EnhancedEncryption,
                                contentDescription = "Shield encryption flag",
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "AES 128-bit Encrypt (Local)",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = PureWhite
                            )
                        }
                        Text(
                            text = "Encrypt object locally with a secure shield key before transmitting to Cloudflare pools.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Switch(
                        checked = encryptClientSide,
                        onCheckedChange = onToggleEncryption,
                        modifier = Modifier.testTag("encrypt_file_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ObsidianBg,
                            checkedTrackColor = ElectricCyan,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = BorderDark
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Source size: ${formatFileSize(fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(editingName) },
                modifier = Modifier.testTag("confirm_upload_dialog_button"),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = ObsidianBg)
            ) {
                Text("Transmit Chunks", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("cancel_upload_dialog_button")
            ) {
                Text("Cancel", color = NeonPink)
            }
        },
        containerColor = DeepGreySurface,
        tonalElevation = 6.dp
    )
}

@Composable
fun EmptyPoolState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = "Empty cloud status indicator",
            tint = BorderDark,
            modifier = Modifier.size(60.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Storage Pool Container Empty",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = PureWhite
        )
        Text(
            text = "Click the Import FAB below at the bottom-right to select a local document and stream binary segments to Cloudflare clusters.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp)
        )
    }
}

// === Helper Functions ===

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun getMimeTypeIcon(mimeType: String): ImageVector {
    return when {
        mimeType.startsWith("image/") -> Icons.Default.Image
        mimeType.startsWith("audio/") -> Icons.Default.Audiotrack
        mimeType.startsWith("video/") -> Icons.Default.VideoLibrary
        mimeType.contains("pdf") -> Icons.Default.PictureAsPdf
        mimeType.contains("zip") || mimeType.contains("tar") || mimeType.contains("rar") -> Icons.Default.FolderZip
        mimeType.contains("text") || mimeType.contains("json") || mimeType.contains("xml") -> Icons.Default.Description
        else -> Icons.AutoMirrored.Default.InsertDriveFile
    }
}

private fun getUriMetaData(context: Context, uri: Uri): Triple<String, Long, String> {
    var name = "unnamed_object"
    var size = 0L
    var mime = "application/octet-stream"
    
    // Attempt cursor column resolving
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
    } catch (e: Exception) {
        Log.e("Dashboard", "Meta resolve error: ${e.message}")
    }

    // Secondary fallback filename resolving from path string
    if (name == "unnamed_object") {
        name = uri.path?.substringAfterLast('/') ?: "unnamed_object"
    }

    // Resolve mime fallback from extension
    val ext = MimeTypeMap.getFileExtensionFromUrl(name)
    if (ext != null) {
        val extensionMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
        if (extensionMime != null) mime = extensionMime
    }

    return Triple(name, size, mime)
}

private fun readUriBytes(context: Context, uri: Uri): ByteArray? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes()
        }
    } catch (e: Throwable) {
        Log.e("Dashboard", "Error reading bytes from URI Stream: ${e.message}", e)
        null
    }
}
