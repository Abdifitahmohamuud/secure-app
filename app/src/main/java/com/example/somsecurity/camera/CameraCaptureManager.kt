package com.example.somsecurity.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.somsecurity.data.SecurityDatabase
import com.example.somsecurity.data.SecurityEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraCaptureManager(
    private val context: Context,
    private val database: SecurityDatabase
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var stopTimerRunnable: Runnable? = null

    val isRecording: Boolean
        get() = activeRecording != null

    private fun getMediaDirectory(subDir: String): File {
        val dir = File(context.filesDir, "security_media/$subDir")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun hasAvailableStorage(requiredBytes: Long): Boolean {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val available = stat.availableBytes
            available >= requiredBytes
        } catch (e: Exception) {
            Log.e(TAG, "Error checking storage capacity", e)
            true // Allow attempt if stat fails
        }
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        onReady: (Boolean, String?) -> Unit
    ) {
        if (!hasCameraPermission()) {
            onReady(false, "Camera permission not granted")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                // Try front camera first, fallback to back camera
                val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    onReady(false, "No compatible camera found on this device")
                    return@addListener
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                val useCases = mutableListOf(imageCapture!!, videoCapture!!)

                if (previewView != null) {
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    useCases.add(preview)
                }

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    *useCases.toTypedArray()
                )

                Log.i(TAG, "CameraX use cases successfully bound")
                onReady(true, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera use cases", e)
                onReady(false, e.localizedMessage ?: "Failed to initialize camera")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun capturePhoto(
        onResult: (success: Boolean, message: String, eventId: Long?) -> Unit
    ) {
        if (!hasCameraPermission()) {
            recordFailedEvent(
                eventType = "MANUAL_PHOTO",
                status = "PERMISSION_DENIED",
                error = "Camera permission not granted",
                onResult = onResult
            )
            return
        }

        val capture = imageCapture
        if (capture == null) {
            recordFailedEvent(
                eventType = "MANUAL_PHOTO",
                status = "FAILED",
                error = "Camera not initialized. Open preview first.",
                onResult = onResult
            )
            return
        }

        // Require at least 20 MB free space
        if (!hasAvailableStorage(20 * 1024 * 1024L)) {
            recordFailedEvent(
                eventType = "MANUAL_PHOTO",
                status = "FAILED",
                error = "Insufficient internal storage space",
                onResult = onResult
            )
            return
        }

        val photoFile = File(
            getMediaDirectory("photos"),
            "photo_${System.currentTimeMillis()}.jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Log.i(TAG, "Photo saved successfully at ${photoFile.absolutePath}")
                    CoroutineScope(Dispatchers.IO).launch {
                        val event = SecurityEventEntity(
                            eventType = "MANUAL_PHOTO",
                            captureStatus = "SUCCESS",
                            mediaPath = photoFile.absolutePath,
                            mediaType = "PHOTO",
                            errorMessage = null
                        )
                        val id = database.securityEventDao().insertEvent(event)
                        mainHandler.post {
                            onResult(true, "Photo captured: ${photoFile.name}", id)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    recordFailedEvent(
                        eventType = "MANUAL_PHOTO",
                        status = "FAILED",
                        error = exception.localizedMessage ?: "Photo capture failed",
                        onResult = onResult
                    )
                }
            }
        )
    }

    fun startTenSecondVideoRecording(
        onProgress: (secondsRemaining: Int) -> Unit,
        onResult: (success: Boolean, message: String, eventId: Long?) -> Unit
    ) {
        if (!hasCameraPermission()) {
            recordFailedEvent(
                eventType = "MANUAL_VIDEO",
                status = "PERMISSION_DENIED",
                error = "Camera permission not granted",
                onResult = onResult
            )
            return
        }

        val currentVideoCapture = videoCapture
        if (currentVideoCapture == null) {
            recordFailedEvent(
                eventType = "MANUAL_VIDEO",
                status = "FAILED",
                error = "Video capture not initialized. Open preview first.",
                onResult = onResult
            )
            return
        }

        if (activeRecording != null) {
            onResult(false, "A recording is already in progress", null)
            return
        }

        // Require at least 50 MB free space for 10-second video
        if (!hasAvailableStorage(50 * 1024 * 1024L)) {
            recordFailedEvent(
                eventType = "MANUAL_VIDEO",
                status = "FAILED",
                error = "Insufficient internal storage space for video recording",
                onResult = onResult
            )
            return
        }

        val videoFile = File(
            getMediaDirectory("videos"),
            "video_${System.currentTimeMillis()}.mp4"
        )
        val fileOutputOptions = FileOutputOptions.Builder(videoFile).build()

        var pendingRecording = currentVideoCapture.output.prepareRecording(context, fileOutputOptions)
        if (hasAudioPermission()) {
            try {
                pendingRecording = pendingRecording.withAudioEnabled()
            } catch (e: SecurityException) {
                Log.w(TAG, "Audio permission revoked while preparing recording: ${e.message}")
            }
        }

        var remainingSeconds = 10
        val countdownRunnable = object : Runnable {
            override fun run() {
                if (remainingSeconds > 0 && activeRecording != null) {
                    onProgress(remainingSeconds)
                    remainingSeconds--
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }

        stopTimerRunnable = Runnable {
            Log.i(TAG, "10-second recording timer expired. Stopping recording...")
            stopRecording()
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    Log.i(TAG, "10-second video recording started")
                    mainHandler.post(countdownRunnable)
                    mainHandler.postDelayed(stopTimerRunnable!!, 10000L)
                }
                is VideoRecordEvent.Finalize -> {
                    cleanUpRecordingTimer()
                    val error = recordEvent.error
                    if (recordEvent.hasError() && error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        Log.e(TAG, "Video recording finalize error: $error")
                        // Clean up partial file if error is severe
                        val isInterrupted = error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ||
                                error == VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED
                        val status = if (isInterrupted) "INTERRUPTED" else "FAILED"
                        val errDesc = "Recording ended with error code $error"
                        recordFailedEvent(
                            eventType = "MANUAL_VIDEO",
                            status = status,
                            error = errDesc,
                            onResult = onResult
                        )
                    } else {
                        Log.i(TAG, "Video recording finalized successfully: ${videoFile.absolutePath}")
                        CoroutineScope(Dispatchers.IO).launch {
                            val event = SecurityEventEntity(
                                eventType = "MANUAL_VIDEO",
                                captureStatus = "SUCCESS",
                                mediaPath = videoFile.absolutePath,
                                mediaType = "VIDEO",
                                errorMessage = null
                            )
                            val id = database.securityEventDao().insertEvent(event)
                            mainHandler.post {
                                onResult(true, "Video saved (10s): ${videoFile.name}", id)
                            }
                        }
                    }
                    activeRecording = null
                }
                else -> {}
            }
        }
    }

    fun stopRecording() {
        cleanUpRecordingTimer()
        activeRecording?.stop()
        activeRecording = null
    }

    private fun cleanUpRecordingTimer() {
        stopTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        stopTimerRunnable = null
    }

    private fun recordFailedEvent(
        eventType: String,
        status: String,
        error: String,
        onResult: (Boolean, String, Long?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val event = SecurityEventEntity(
                eventType = eventType,
                captureStatus = status,
                mediaPath = null,
                mediaType = null,
                errorMessage = error
            )
            val id = database.securityEventDao().insertEvent(event)
            mainHandler.post {
                onResult(false, error, id)
            }
        }
    }

    fun shutdown() {
        cleanUpRecordingTimer()
        activeRecording?.stop()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraCaptureManager"
    }
}

