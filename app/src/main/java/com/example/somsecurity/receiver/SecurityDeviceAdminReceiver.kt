package com.example.somsecurity.receiver

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.somsecurity.camera.LockscreenCaptureActivity
import com.example.somsecurity.data.SecurityDatabase
import com.example.somsecurity.data.SecurityEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SecurityDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled")
    }

    @Suppress("DEPRECATION")
    override fun onPasswordFailed(context: Context, intent: Intent) {
        Log.w(TAG, "onPasswordFailed broadcast received")
        handlePasswordFailed(context)
    }

    @Suppress("DEPRECATION")
    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        Log.i(TAG, "Password unlock succeeded")
    }

    private fun handlePasswordFailed(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

        val attempts = try {
            val count = dpm?.getCurrentFailedPasswordAttempts() ?: -1
            if (count <= 0) 1 else count
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read getCurrentFailedPasswordAttempts", e)
            1
        }

        Log.w(TAG, "Security alert: Failed unlock attempt detected! Recorded attempts: $attempts")

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val hasOverlayPermission = Settings.canDrawOverlays(context)

        if (hasOverlayPermission && hasCameraPermission) {
            Log.i(TAG, "Overlay & Camera permissions granted. Launching LockscreenCaptureActivity...")
            try {
                val captureIntent = Intent(context, LockscreenCaptureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    putExtra(LockscreenCaptureActivity.EXTRA_ATTEMPTS, attempts)
                }
                context.startActivity(captureIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch LockscreenCaptureActivity", e)
                recordEventDirectly(context, attempts, "FAILED", "Failed to start capture activity: ${e.message}")
            }
        } else {
            val reason = when {
                !hasOverlayPermission -> "Display over other apps (SYSTEM_ALERT_WINDOW) permission is required to capture photo on lock screen."
                else -> "Camera permission not granted."
            }
            val status = if (!hasOverlayPermission) "OVERLAY_PERMISSION_NEEDED" else "PERMISSION_DENIED"
            Log.w(TAG, "Cannot launch capture activity: $reason")
            recordEventDirectly(context, attempts, status, reason)
        }
    }

    private fun recordEventDirectly(
        context: Context,
        attempts: Int,
        status: String,
        error: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = SecurityDatabase.getInstance(context)
                val event = SecurityEventEntity(
                    eventType = "FAILED_UNLOCK",
                    failedAttemptsCount = attempts,
                    captureStatus = status,
                    mediaPath = null,
                    mediaType = null,
                    errorMessage = error
                )
                db.securityEventDao().insertEvent(event)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write event to database", e)
            }
        }
    }

    companion object {
        private const val TAG = "SecurityDeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, SecurityDeviceAdminReceiver::class.java)
        }
    }
}
