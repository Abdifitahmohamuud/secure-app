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

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelector: CameraSelector? = null
    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentPreview: Preview? = null

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
            stat.availableBytes >= requiredBytes
        } catch (e: Exception) {
            Log.e(TAG, "Error checking storage capacity", e)
            true
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

        currentLifecycleOwner = lifecycleOwner

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    onReady(false, "No compatible camera found on this device")
                    return@addListener
                }
                cameraSelector = selector

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.HD, Quality.SD, Quality.LOWEST),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                if (previewView != null) {
                    currentPreview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                }

                // By default, bind Preview and ImageCapture for instant photo readiness
                val defaultUseCases = mutableListOf<androidx.camera.core.UseCase>(imageCapture!!)
                currentPreview?.let { defaultUseCases.add(it) }

                provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    *defaultUseCases.toTypedArray()
                )

                Log.i(TAG, "CameraX photo/preview mode bound successfully")
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
            recordFailedEvent("MANUAL_PHOTO", "PERMISSION_DENIED", "Camera permission not granted", onResult)
            return
        }

        val capture = imageCapture
        if (capture == null) {
            recordFailedEvent("MANUAL_PHOTO", "FAILED", "Camera not initialized. Please wait for preview.", onResult)
            return
        }

        if (!hasAvailableStorage(20 * 1024 * 1024L)) {
            recordFailedEvent("MANUAL_PHOTO", "FAILED", "Insufficient internal storage space", onResult)
            return
        }

        val photoFile = File(getMediaDirectory("photos"), "photo_${System.currentTimeMillis()}.jpg")
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
                    recordFailedEvent("MANUAL_PHOTO", "FAILED", exception.localizedMessage ?: "Photo capture failed", onResult)
                }
            }
        )
    }

    fun startTenSecondVideoRecording(
        onProgress: (secondsRemaining: Int) -> Unit,
        onResult: (success: Boolean, message: String, eventId: Long?) -> Unit
    ) {
        if (!hasCameraPermission()) {
            recordFailedEvent("MANUAL_VIDEO", "PERMISSION_DENIED", "Camera permission not granted", onResult)
            return
        }

        if (activeRecording != null) {
            onResult(false, "A recording is already in progress", null)
            return
        }

        if (!hasAvailableStorage(50 * 1024 * 1024L)) {
            recordFailedEvent("MANUAL_VIDEO", "FAILED", "Insufficient storage space for video recording", onResult)
            return
        }

        val provider = cameraProvider
        val selector = cameraSelector
        val lifecycle = currentLifecycleOwner

        if (provider == null || selector == null || lifecycle == null) {
            recordFailedEvent("MANUAL_VIDEO", "FAILED", "Camera system not ready. Waiting for initialization.", onResult)
            return
        }

        // On Limited/Legacy hardware (Samsung Galaxy A14 front camera), binding Preview + ImageCapture + VideoCapture
        // simultaneously exceeds camera stream limits and causes ERROR_NO_VALID_DATA (error 8).
        // Solution: Switch to VideoCapture stream exclusively during recording!
        try {
            provider.unbindAll()
            val videoUseCases = mutableListOf<androidx.camera.core.UseCase>(videoCapture!!)
            currentPreview?.let { videoUseCases.add(it) }

            provider.bindToLifecycle(
                lifecycle,
                selector,
                *videoUseCases.toTypedArray()
            )
            Log.i(TAG, "Switched to VideoCapture exclusive stream on camera")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind video stream", e)
            recordFailedEvent("MANUAL_VIDEO", "FAILED", "Failed to bind video stream: ${e.message}", onResult)
            return
        }

        // Brief 250ms pause to ensure Camera HAL finishes surface configuration and activates stream
        mainHandler.postDelayed({
            executeRecording(onProgress, onResult)
        }, 250L)
    }

    private fun executeRecording(
        onProgress: (secondsRemaining: Int) -> Unit,
        onResult: (success: Boolean, message: String, eventId: Long?) -> Unit
    ) {
        val currentVideoCapture = videoCapture ?: return

        val videoFile = File(getMediaDirectory("videos"), "video_${System.currentTimeMillis()}.mp4")
        val fileOutputOptions = FileOutputOptions.Builder(videoFile).build()

        var pendingRecording = currentVideoCapture.output.prepareRecording(context, fileOutputOptions)
        if (hasAudioPermission() && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
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
            Log.i(TAG, "10-second timer reached. Stopping video recording...")
            stopRecording()
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    Log.i(TAG, "Video recording actively streaming data")
                    mainHandler.post(countdownRunnable)
                    mainHandler.postDelayed(stopTimerRunnable!!, 10000L)
                }
                is VideoRecordEvent.Finalize -> {
                    cleanUpRecordingTimer()
                    val error = recordEvent.error
                    val hasError = recordEvent.hasError() && error != VideoRecordEvent.Finalize.ERROR_NONE

                    // Rebind photo use cases for subsequent photo captures
                    restorePhotoUseCases()

                    if (hasError) {
                        Log.e(TAG, "Video recording finalize error: $error")
                        val status = if (error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) "INTERRUPTED" else "FAILED"
                        val errDesc = "Video recording ended with error code $error"
                        recordFailedEvent("MANUAL_VIDEO", status, errDesc, onResult)
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

    private fun restorePhotoUseCases() {
        try {
            val provider = cameraProvider ?: return
            val selector = cameraSelector ?: return
            val lifecycle = currentLifecycleOwner ?: return
            val capture = imageCapture ?: return

            provider.unbindAll()
            val photoUseCases = mutableListOf<androidx.camera.core.UseCase>(capture)
            currentPreview?.let { photoUseCases.add(it) }

            provider.bindToLifecycle(lifecycle, selector, *photoUseCases.toTypedArray())
            Log.i(TAG, "Restored photo/preview stream on camera")
        } catch (e: Exception) {
            Log.w(TAG, "Error restoring photo use cases", e)
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
