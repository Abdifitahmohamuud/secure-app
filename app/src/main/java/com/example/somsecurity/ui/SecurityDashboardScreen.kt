package com.example.somsecurity.ui

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.somsecurity.data.SecurityEventEntity
import com.example.somsecurity.receiver.SecurityDeviceAdminReceiver
import com.example.somsecurity.ui.viewmodel.SecurityViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityDashboardScreen(
    viewModel: SecurityViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val events by viewModel.events.collectAsState()
    val isAdminActive by viewModel.isAdminActive.collectAsState()
    val hasCameraPermission by viewModel.hasCameraPermission.collectAsState()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val countdown by viewModel.recordingCountdown.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var showCameraPreview by remember { mutableStateOf(true) }
    var previewViewInstance by remember { mutableStateOf<PreviewView?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var viewingPhotoPath by remember { mutableStateOf<String?>(null) }

    // Admin Activation Launcher
    val adminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshAdminStatus()
    }

    // Overlay Permission Launcher
    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshOverlayPermission()
    }

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.refreshCameraPermission()
        if (permissions[Manifest.permission.CAMERA] == true) {
            previewViewInstance?.let { pv ->
                viewModel.captureManager.bindCameraUseCases(lifecycleOwner, pv) { ok, err ->
                    previewError = err
                }
            }
        }
    }

    val playVideo: (String) -> Unit = { path ->
        try {
            val file = File(path)
            if (file.exists()) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } else {
                viewModel.setStatusMessage("Video file not found")
            }
        } catch (e: Exception) {
            viewModel.setStatusMessage("Error playing video: ${e.message}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SomSecuri Monitor", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Offline Local Security MVP", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.refreshAdminStatus()
                        viewModel.refreshCameraPermission()
                        viewModel.refreshOverlayPermission()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Device Admin Status Card
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAdminActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isAdminActive) Icons.Default.Security else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isAdminActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Device Administration",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(if (isAdminActive) "ACTIVE" else "INACTIVE", fontWeight = FontWeight.Bold)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isAdminActive) {
                                "Device Admin is active. Failed PIN/Password unlock attempts will be logged locally."
                            } else {
                                "Required for detecting failed unlock attempts. Tap below to activate."
                            },
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (!isAdminActive) {
                            Button(
                                onClick = {
                                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                        putExtra(
                                            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                            SecurityDeviceAdminReceiver.getComponentName(context)
                                        )
                                        putExtra(
                                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                            "Enables local logging of failed PIN/pattern/password unlock attempts."
                                        )
                                    }
                                    adminLauncher.launch(intent)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AdminPanelSettings, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Activate Device Admin")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                                    val admin = SecurityDeviceAdminReceiver.getComponentName(context)
                                    dpm?.removeActiveAdmin(admin)
                                    viewModel.refreshAdminStatus()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Deactivate Device Admin")
                            }
                        }
                    }
                }
            }

            // Camera Permission Card
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasCameraPermission) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Camera & Mic Permission", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(if (hasCameraPermission) "GRANTED" else "DENIED", fontWeight = FontWeight.Bold)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (hasCameraPermission) {
                                "Permissions granted for manual photo capture and 10s video test."
                            } else {
                                "Camera & Microphone permissions are required to run manual capture tests."
                            },
                            fontSize = 13.sp
                        )
                        if (!hasCameraPermission) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.CAMERA,
                                            Manifest.permission.RECORD_AUDIO
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Grant Camera & Mic Permissions")
                            }
                        }
                    }
                }
            }

            // Display Over Other Apps (Overlay) Card - Required for CrookCatcher-style capture
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasOverlayPermission) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Layers, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Appear On Top (Lockscreen)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(if (hasOverlayPermission) "GRANTED" else "REQUIRED", fontWeight = FontWeight.Bold)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (hasOverlayPermission) {
                                "Active: App is permitted to take a photo on the lock screen when a wrong PIN is entered."
                            } else {
                                "Required by Android 10+ to display the capture window over the lock screen and snap the intruder photo."
                            },
                            fontSize = 13.sp
                        )
                        if (!hasOverlayPermission) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    overlayLauncher.launch(intent)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Grant 'Appear on Top' Permission")
                            }
                        }
                    }
                }
            }

            // Android 15 Platform Feasibility Policy Notice
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Android 15 Capture Feasibility Notice",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "• Credential-based unlock failures (PIN/Password/Pattern) are detected via Device Admin API.\n" +
                                    "• CrookCatcher Lock-Screen Architecture is ENABLED: When a wrong PIN is entered and 'Appear on top' is granted, the app launches a translucent lockscreen capture activity to immediately photograph the intruder.\n" +
                                    "• All photos and event logs are stored locally offline in app-private storage.",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Camera Testing Section
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Manual Camera Test", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Switch(
                                checked = showCameraPreview,
                                onCheckedChange = { showCameraPreview = it }
                            )
                        }

                        Text(
                            text = if (showCameraPreview) "Live preview is ON" else "Toggle preview ON to activate camera viewfinder",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (showCameraPreview) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        PreviewView(ctx).also { pv ->
                                            previewViewInstance = pv
                                            if (hasCameraPermission) {
                                                viewModel.captureManager.bindCameraUseCases(
                                                    lifecycleOwner,
                                                    pv
                                                ) { _, err -> previewError = err }
                                            }
                                        }
                                    }
                                )
                                previewError?.let { err ->
                                    Text(
                                        text = err,
                                        color = Color.Red,
                                        modifier = Modifier.padding(8.dp),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Capture buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!showCameraPreview) {
                                        showCameraPreview = true
                                    }
                                    viewModel.capturePhoto()
                                },
                                enabled = hasCameraPermission && !isRecording,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Photo", fontSize = 13.sp)
                            }

                            if (!isRecording) {
                                Button(
                                    onClick = {
                                        if (!showCameraPreview) {
                                            showCameraPreview = true
                                        }
                                        viewModel.startTenSecondVideo()
                                    },
                                    enabled = hasCameraPermission,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Videocam, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Record 10s", fontSize = 13.sp)
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.stopVideo() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Stop (${countdown}s)", fontSize = 13.sp)
                                }
                            }
                        }

                        statusMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Local Security Events Log Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Security Events Log (${events.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    if (events.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearAllEvents() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear All", fontSize = 12.sp)
                        }
                    }
                }
            }

            if (events.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.EventNote,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "No security events recorded yet",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Trigger a manual photo/video test or lock screen failed PIN attempt to test.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            } else {
                items(events, key = { it.id }) { event ->
                    SecurityEventItem(
                        event = event,
                        onDelete = { viewModel.deleteEvent(event) },
                        onPhotoClick = { path -> viewingPhotoPath = path },
                        onVideoClick = { path -> playVideo(path) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // Photo Viewer Dialog
    viewingPhotoPath?.let { path ->
        Dialog(onDismissRequest = { viewingPhotoPath = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Captured Test Photo",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AsyncImage(
                        model = File(path),
                        contentDescription = "Full test photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = path,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewingPhotoPath = null },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
fun SecurityEventItem(
    event: SecurityEventEntity,
    onDelete: () -> Unit,
    onPhotoClick: (String) -> Unit,
    onVideoClick: (String) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeFormatted = remember(event.timestamp) { dateFormat.format(Date(event.timestamp)) }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Icon
            val (icon, iconColor) = when (event.eventType) {
                "FAILED_UNLOCK" -> Pair(Icons.Default.LockClock, Color(0xFFE53935))
                "MANUAL_PHOTO" -> Pair(Icons.Default.PhotoCamera, Color(0xFF1E88E5))
                "MANUAL_VIDEO" -> Pair(Icons.Default.Videocam, Color(0xFF43A047))
                else -> Pair(Icons.Default.Info, Color.Gray)
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(iconColor.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = event.eventType,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = timeFormatted,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Status chip
                val (statusColor, statusBg) = when (event.captureStatus) {
                    "SUCCESS" -> Pair(Color(0xFF2E7D32), Color(0xFFE8F5E9))
                    "BLOCKED_BY_OS" -> Pair(Color(0xFFE65100), Color(0xFFFFF3E0))
                    "OVERLAY_PERMISSION_NEEDED" -> Pair(Color(0xFFE65100), Color(0xFFFFF3E0))
                    "INTERRUPTED" -> Pair(Color(0xFFF57F17), Color(0xFFFFFDE7))
                    else -> Pair(Color(0xFFC62828), Color(0xFFFFEBEE))
                }

                Surface(
                    color = statusBg,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = event.captureStatus,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (event.failedAttemptsCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Failed attempts reported: ${event.failedAttemptsCount}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                event.errorMessage?.let { err ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = err,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 15.sp
                    )
                }

                event.mediaPath?.let { path ->
                    Spacer(modifier = Modifier.height(6.dp))
                    if (event.mediaType == "PHOTO") {
                        val file = File(path)
                        if (file.exists()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { onPhotoClick(path) }
                                    .padding(vertical = 2.dp)
                            ) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = "Thumbnail",
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = file.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Tap to view photo",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            Text("Media file removed", fontSize = 11.sp, color = Color.Gray)
                        }
                    } else if (event.mediaType == "VIDEO") {
                        val file = File(path)
                        if (file.exists()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { onVideoClick(path) }
                                    .padding(vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF2E7D32).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play video",
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = file.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Tap to play video (${file.length() / 1024} KB)",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            Text("Video file removed", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

