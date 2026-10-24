package com.example.somsecurity.ui.viewmodel

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.somsecurity.SecurityApp
import com.example.somsecurity.camera.CameraCaptureManager
import com.example.somsecurity.data.SecurityEventEntity
import com.example.somsecurity.receiver.SecurityDeviceAdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SecurityViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as SecurityApp).database
    val captureManager = CameraCaptureManager(application, db)

    val events: StateFlow<List<SecurityEventEntity>> = db.securityEventDao()
        .getAllEvents()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isAdminActive = MutableStateFlow(false)
    val isAdminActive: StateFlow<Boolean> = _isAdminActive.asStateFlow()

    private val _hasCameraPermission = MutableStateFlow(false)
    val hasCameraPermission: StateFlow<Boolean> = _hasCameraPermission.asStateFlow()

    private val _hasOverlayPermission = MutableStateFlow(false)
    val hasOverlayPermission: StateFlow<Boolean> = _hasOverlayPermission.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingCountdown = MutableStateFlow(10)
    val recordingCountdown: StateFlow<Int> = _recordingCountdown.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        refreshAdminStatus()
        refreshCameraPermission()
        refreshOverlayPermission()
    }

    fun refreshAdminStatus() {
        val context = getApplication<Application>()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = SecurityDeviceAdminReceiver.getComponentName(context)
        _isAdminActive.value = dpm?.isAdminActive(adminComponent) == true
    }

    fun refreshCameraPermission() {
        _hasCameraPermission.value = captureManager.hasCameraPermission()
    }

    fun refreshOverlayPermission() {
        val context = getApplication<Application>()
        _hasOverlayPermission.value = Settings.canDrawOverlays(context)
    }

    fun setStatusMessage(msg: String?) {
        _statusMessage.value = msg
    }

    fun capturePhoto() {
        _statusMessage.value = "Capturing photo..."
        captureManager.capturePhoto { success, msg, _ ->
            _statusMessage.value = if (success) "✓ $msg" else "✗ $msg"
        }
    }

    fun startTenSecondVideo() {
        _isRecording.value = true
        _recordingCountdown.value = 10
        _statusMessage.value = "Starting 10-second video recording..."

        captureManager.startTenSecondVideoRecording(
            onProgress = { secondsRemaining ->
                _recordingCountdown.value = secondsRemaining
            },
            onResult = { success, msg, _ ->
                _isRecording.value = false
                _statusMessage.value = if (success) "✓ $msg" else "✗ $msg"
            }
        )
    }

    fun stopVideo() {
        captureManager.stopRecording()
        _isRecording.value = false
        _statusMessage.value = "Recording stopped by user"
    }

    fun deleteEvent(event: SecurityEventEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete associated file if it exists
            event.mediaPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
            db.securityEventDao().deleteById(event.id)
        }
    }

    fun clearAllEvents() {
        viewModelScope.launch(Dispatchers.IO) {
            val mediaDir = File(getApplication<Application>().filesDir, "security_media")
            if (mediaDir.exists()) {
                mediaDir.deleteRecursively()
            }
            db.securityEventDao().deleteAll()
            _statusMessage.value = "All security events and local media cleared"
        }
    }

    override fun onCleared() {
        super.onCleared()
        captureManager.shutdown()
    }
}

