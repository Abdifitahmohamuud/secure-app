package com.example.somsecurity.camera

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.somsecurity.SecurityApp
import com.example.somsecurity.data.SecurityEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class LockscreenCaptureActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var recording: Recording? = null
    private var captureCompleted = false
    private var attemptsCount = 1

    private val timeoutRunnable = Runnable {
        Log.w(TAG, "Video capture timed out. Closing activity.")
        finishSafely("FAILED", "Video capture timed out")
    }

    private val stopRecordingRunnable = Runnable {
        Log.i(TAG, "10-second failed-unlock video duration reached")
        recording?.stop()
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
                acquire(CAPTURE_TIMEOUT_MS)
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
            text = "Recording security video..."
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

        mainHandler.postDelayed(timeoutRunnable, CAPTURE_TIMEOUT_MS)

        startVideoCapture(previewView)
    }

    private fun startVideoCapture(previewView: PreviewView) {
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

                val mediaDir = File(filesDir, "security_media/videos")
                if (!mediaDir.exists() && !mediaDir.mkdirs()) {
                    finishSafely("FAILED", "Could not create video storage directory")
                    return@addListener
                }
                if (StatFs(filesDir.absolutePath).availableBytes < MIN_VIDEO_STORAGE_BYTES) {
                    finishSafely("FAILED", "Insufficient storage space for video recording")
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.HD, Quality.SD, Quality.LOWEST),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    videoCapture
                )

                startRecording(videoCapture, mediaDir)

            } catch (e: Exception) {
                Log.e(TAG, "Error binding camera for lockscreen capture", e)
                finishSafely("FAILED", e.localizedMessage ?: "Failed to initialize camera")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording(videoCapture: VideoCapture<Recorder>, mediaDir: File) {
        val videoFile = File(mediaDir, "intruder_${System.currentTimeMillis()}.mp4")
        recording = videoCapture.output
            .prepareRecording(this, FileOutputOptions.Builder(videoFile).build())
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.i(TAG, "Failed-unlock video recording started")
                        mainHandler.postDelayed(stopRecordingRunnable, VIDEO_DURATION_MS)
                    }
                    is VideoRecordEvent.Finalize -> {
                        mainHandler.removeCallbacks(stopRecordingRunnable)
                        recording = null
                        if (captureCompleted) {
                            videoFile.delete()
                            return@start
                        }
                        if (event.hasError()) {
                            Log.e(TAG, "Failed-unlock video recording ended with error ${event.error}")
                            videoFile.delete()
                            finishSafely("FAILED", "Video recording failed with error ${event.error}")
                        } else {
                            saveVideoEvent(videoFile)
                        }
                    }
                }
            }
    }

    private fun saveVideoEvent(videoFile: File) {
        if (captureCompleted) return
        captureCompleted = true
        mainHandler.removeCallbacks(timeoutRunnable)
        releaseWakeLock()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = (application as SecurityApp).database
                val event = SecurityEventEntity(
                    eventType = "FAILED_UNLOCK",
                    failedAttemptsCount = attemptsCount,
                    captureStatus = "SUCCESS",
                    mediaPath = videoFile.absolutePath,
                    mediaType = "VIDEO",
                    errorMessage = null
                )
                val rowId = db.securityEventDao().insertEvent(event)
                Log.i(TAG, "Intruder video event inserted with rowId: $rowId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert intruder video event", e)
            } finally {
                mainHandler.post { finish() }
            }
        }
    }

    private fun finishSafely(statusIfFailed: String?, errorMessage: String?) {
        if (captureCompleted) return
        captureCompleted = true
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(stopRecordingRunnable)
        recording?.stop()
        recording = null
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
                    Log.e(TAG, "Error logging failed video capture event", e)
                } finally {
                    mainHandler.post { finish() }
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
        mainHandler.removeCallbacks(stopRecordingRunnable)
        recording?.stop()
        recording = null
        releaseWakeLock()
    }

    companion object {
        private const val TAG = "LockscreenCapture"
        const val EXTRA_ATTEMPTS = "extra_attempts_count"
        private const val VIDEO_DURATION_MS = 10_000L
        private const val CAPTURE_TIMEOUT_MS = 30_000L
        private const val MIN_VIDEO_STORAGE_BYTES = 50 * 1024 * 1024L
    }
}
