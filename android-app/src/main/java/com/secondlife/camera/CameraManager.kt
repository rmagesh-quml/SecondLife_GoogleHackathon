package com.secondlife.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX wrapper for the Android app module.
 * Captures a single 768×768 center-cropped Bitmap on demand.
 *
 * Contract → InferenceSession: android.graphics.Bitmap, 768×768 px, ARGB_8888
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    companion object {
        const val TARGET_SIZE = 768
    }

    private val executor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var previewUseCase: Preview? = null

    suspend fun initialize() {
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try   { cont.resume(future.get()) }
                catch (e: Exception) { cont.resumeWithException(e) }
            }, executor)
        }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
        cameraProvider = provider
        imageCapture   = capture
    }

    suspend fun captureFrame(): Bitmap {
        val capture = checkNotNull(imageCapture) { "Call initialize() before captureFrame()" }

        val raw: Bitmap = suspendCancellableCoroutine { cont ->
            capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = image.toBitmap()
                    image.close()
                    cont.resume(bmp)
                }
                override fun onError(e: ImageCaptureException) {
                    cont.resumeWithException(e)
                }
            })
        }

        return centerCrop(raw, TARGET_SIZE)
    }

    // ── Viewfinder (in-app camera overlay) ────────────────────────────────────

    /**
     * Attach a live preview to [surfaceProvider] (from a [PreviewView]).
     * Rebinds CameraX with both Preview + ImageCapture so capture still works.
     * Call this when the camera overlay becomes visible.
     */
    fun startPreview(surfaceProvider: Preview.SurfaceProvider) {
        val provider = cameraProvider ?: return
        val capture  = imageCapture  ?: return
        val preview  = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
        previewUseCase = preview
        try {
            // Rebind with both use-cases; unbindAll first to avoid duplicate-binding errors.
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
        } catch (e: Exception) {
            android.util.Log.e("CameraManager", "startPreview failed: ${e.message}")
        }
    }

    /**
     * Remove the preview and restore the ImageCapture-only binding.
     * Call this when the camera overlay is dismissed.
     */
    fun stopPreview() {
        val provider = cameraProvider ?: return
        val capture  = imageCapture  ?: return
        previewUseCase = null
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
        } catch (e: Exception) {
            android.util.Log.e("CameraManager", "stopPreview failed: ${e.message}")
        }
    }

    fun release() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture   = null
        previewUseCase = null
    }

    private fun centerCrop(src: Bitmap, size: Int): Bitmap {
        val scale  = size.toFloat() / minOf(src.width, src.height)
        val scaledW = (src.width  * scale).toInt()
        val scaledH = (src.height * scale).toInt()
        val scaled  = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val x = (scaledW - size) / 2
        val y = (scaledH - size) / 2
        val cropped = Bitmap.createBitmap(scaled, x, y, size, size)
        if (scaled !== src) scaled.recycle()
        return cropped
    }
}
