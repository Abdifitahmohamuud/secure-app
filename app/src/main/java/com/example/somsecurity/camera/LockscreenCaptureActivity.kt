package com.example.somsecurity.camera

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.somsecurity.SecurityApp
import com.example.somsecurity.data.SecurityEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LockscreenCaptureActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraExecutor: ExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isCapturing = false
    private var attemptsCount = 1

    private val timeoutRunnable = Runnable {
        Log.w(TAG, "Capture timed out after 4 seconds. Closing activity.")
        finishSafely("FAILED", "Camera capture timed out")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must configure lockscreen flags as early as possible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        super.onCreate(savedInstanceState)

        attemptsCount = intent.getIntExtra(EXTRA_ATTEMPTS, 1)
        Log.i(TAG, "LockscreenCaptureActivity launched over lockscreen! Attempts: $attemptsCount")

        // Acquire WakeLock to keep screen on during capture
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "somsecurity:capture_lock"
            ).apply {
                acquire(5000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake lock", e)
        }

        // Layout: black screen with active previewView to ensure camera pipeline streams frames
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(previewView)

        val statusText = TextView(this).apply {
            text = "Securing..."
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        rootLayout.addView(statusText)

        setContentView(rootLayout)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 4-second safety watchdog
        mainHandler.postDelayed(timeoutRunnable, 4000L)

        startCapture(previewView)
    }

    private fun startCapture(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    finishSafely("FAILED", "No front or back camera found on device")
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                // Brief 350ms delay for auto-exposure convergence
                mainHandler.postDelayed({
                    takePhoto(imageCapture)
                }, 350L)

            } catch (e: Exception) {
                Log.e(TAG, "Error binding camera for lockscreen capture", e)
                finishSafely("FAILED", e.localizedMessage ?: "Failed to initialize camera")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto(imageCapture: ImageCapture) {
        if (isCapturing) return
        isCapturing = true

        val mediaDir = File(filesDir, "security_media/photos").apply { mkdirs() }
        val photoFile = File(mediaDir, "intruder_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor ?: ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.i(TAG, "Intruder photo saved successfully: ${photoFile.absolutePath}")
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = (application as SecurityApp).database
                            val event = SecurityEventEntity(
                                eventType = "FAILED_UNLOCK",
                                failedAttemptsCount = attemptsCount,
                                captureStatus = "SUCCESS",
                                mediaPath = photoFile.absolutePath,
                                mediaType = "PHOTO",
                                errorMessage = null
                            )
                            val rowId = db.securityEventDao().insertEvent(event)
                            Log.i(TAG, "Intruder event inserted with rowId: $rowId")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to insert intruder event to database", e)
                        } finally {
                            mainHandler.post {
                                finishSafely(null, null)
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Intruder photo capture error", exception)
                    finishSafely("FAILED", exception.localizedMessage ?: "Camera capture error")
                }
            }
        )
    }

    private fun finishSafely(statusIfFailed: String?, errorMessage: String?) {
        mainHandler.removeCallbacks(timeoutRunnable)
        releaseWakeLock()

        if (statusIfFailed != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = (application as SecurityApp).database
                    val event = SecurityEventEntity(
                        eventType = "FAILED_UNLOCK",
                        failedAttemptsCount = attemptsCount,
                        captureStatus = statusIfFailed,
                        mediaPath = null,
                        mediaType = null,
                        errorMessage = errorMessage
                    )
                    db.securityEventDao().insertEvent(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error logging failed capture event", e)
                } finally {
                    mainHandler.post {
                        finish()
                    }
                }
            }
        } else {
            finish()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wakelock", e)
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(timeoutRunnable)
        releaseWakeLock()
        cameraExecutor?.shutdown()
    }

    companion object {
        private const val TAG = "LockscreenCapture"
        const val EXTRA_ATTEMPTS = "extra_attempts_count"
    }
}
