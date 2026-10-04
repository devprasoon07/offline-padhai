package com.devprasoon.offlinepadhai

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX wrapper: full-bleed preview + photo capture.
 *
 * UI se alag rakha hai taaki MainActivity sirf flow sambhale.
 * takePhoto ka callback background thread pe aata hai — UI kaam
 * hamesha main thread pe karo.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {

    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "CameraManager"
    }

    /** Preview shuru karo. Pehle se chal raha ho to dobara bind ho jayega. */
    fun startCamera(onError: (String) -> Unit = {}) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val cameraProvider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "startCamera failed", e)
                onError("Camera khul nahi paya.")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Photo lo. File app ke cache dir me save hoti hai.
     * @param onResult null = capture fail hua.
     */
    fun takePhoto(onResult: (File?) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onResult(null)
            return
        }
        val photoFile = File(context.cacheDir, "padhai_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onResult(photoFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "takePhoto failed", exception)
                    onResult(null)
                }
            }
        )
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
