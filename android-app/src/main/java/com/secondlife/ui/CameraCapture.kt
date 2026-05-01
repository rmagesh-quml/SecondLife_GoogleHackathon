package com.secondlife.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.secondlife.camera.CameraManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen in-app camera overlay — works like Claude/ChatGPT's photo capture.
 *
 * Shows a live viewfinder backed by CameraX (no app-switch needed).
 * Two capture paths:
 *   1. Shutter button → CameraX snapshot at native resolution, centre-cropped to 768 px
 *   2. Gallery icon   → system photo picker, bitmap decoded and scaled to 768 px
 *
 * After capture the bitmap is handed back via [onImageCaptured]; the caller is
 * responsible for calling [CameraManager.stopPreview] if it has not been called yet
 * (this composable calls it automatically on capture and on dismiss).
 */
@Composable
fun CameraCapture(
    cameraManager: CameraManager,
    onImageCaptured: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    var isCapturing by remember { mutableStateOf(false) }

    // Shutter button press-scale animation
    val shutterScale by animateFloatAsState(
        targetValue   = if (isCapturing) 0.88f else 1f,
        animationSpec = tween(durationMillis = 100),
        label         = "shutter",
    )

    // ── Gallery picker ────────────────────────────────────────────────────────
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = loadAndScaleBitmapFromUri(context, uri)
            if (bmp != null) {
                cameraManager.stopPreview()
                onImageCaptured(bmp)
            }
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ── Live viewfinder ─────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    cameraManager.startPreview(surfaceProvider)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Subtle dark gradient at top & bottom so buttons are legible
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.TopCenter)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.70f))
                    )
                )
        )

        // ── Close button ─────────────────────────────────────────────────────
        IconButton(
            onClick  = {
                cameraManager.stopPreview()
                onDismiss()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(
                imageVector        = Icons.Default.Close,
                contentDescription = "Close camera",
                tint               = Color.White,
                modifier           = Modifier.size(28.dp),
            )
        }

        // Hint text
        Text(
            text     = "Point at the injury or scene",
            color    = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 14.dp),
        )

        // ── Bottom controls ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            // Gallery picker
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Default.PhotoLibrary,
                    contentDescription = "Pick from gallery",
                    tint               = Color.White,
                    modifier           = Modifier.size(26.dp),
                )
            }

            // Shutter button — outer ring + inner disc (Claude/ChatGPT style)
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .scale(shutterScale)
                    .border(3.5.dp, Color.White, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(enabled = !isCapturing) {
                        isCapturing = true
                        scope.launch {
                            runCatching { cameraManager.captureFrame() }
                                .onSuccess { bmp ->
                                    cameraManager.stopPreview()
                                    onImageCaptured(bmp)
                                }
                                .onFailure {
                                    android.util.Log.e("CameraCapture", "Capture failed: ${it.message}")
                                }
                            isCapturing = false
                        }
                    },
            )

            // Right spacer matches gallery button width for visual balance
            Spacer(Modifier.size(56.dp))
        }
    }

    // Release preview when this composable leaves the composition
    DisposableEffect(Unit) {
        onDispose { cameraManager.stopPreview() }
    }
}

// ── Gallery bitmap loading ─────────────────────────────────────────────────────

/**
 * Load a gallery [Uri] as a [Bitmap], correcting EXIF rotation and
 * downscaling to max 768 px on the longer side (matches CameraManager's target).
 * Runs on [Dispatchers.IO].
 */
private suspend fun loadAndScaleBitmapFromUri(
    context: android.content.Context,
    uri: Uri,
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        // Decode full bitmap
        val raw: Bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return@withContext null

        // Read EXIF orientation to fix rotation (common with gallery images)
        val orientationDeg: Float = context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = android.media.ExifInterface(stream)
            when (exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        // Apply rotation if needed
        val rotated = if (orientationDeg != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(orientationDeg) }
            android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                .also { if (it !== raw) raw.recycle() }
        } else {
            raw
        }

        // Scale down to 768 px max dimension (same contract as CameraManager)
        val maxDim = CameraManager.TARGET_SIZE
        val w = rotated.width; val h = rotated.height
        if (w <= maxDim && h <= maxDim) return@withContext rotated
        val scale = maxDim.toFloat() / maxOf(w, h)
        Bitmap.createScaledBitmap(rotated, (w * scale).toInt(), (h * scale).toInt(), true)
            .also { if (it !== rotated) rotated.recycle() }
    } catch (e: Exception) {
        android.util.Log.e("CameraCapture", "Gallery load failed: ${e.message}")
        null
    }
}
