package com.secondlife.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
 * After capture, it shows a confirmation preview before returning the bitmap.
 */
@Composable
fun CameraCapture(
    cameraManager: CameraManager,
    onImageCaptured: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isCapturing    by remember { mutableStateOf(false) }

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
                capturedBitmap = bmp
            }
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (capturedBitmap == null) {
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

            // Gradient overlays
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

                // Shutter button
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
                                        capturedBitmap = bmp
                                    }
                                    .onFailure {
                                        android.util.Log.e("CameraCapture", "Capture failed: ${it.message}")
                                    }
                                isCapturing = false
                            }
                        },
                )

                // Spacer for balance
                Spacer(Modifier.size(56.dp))
            }
        } else {
            // ── Confirmation Preview ───────────────────────────────────────────
            ConfirmationPreview(
                bitmap    = capturedBitmap!!,
                onRetake  = { capturedBitmap = null },
                onConfirm = {
                    cameraManager.stopPreview()
                    onImageCaptured(capturedBitmap!!)
                }
            )
        }
    }

    // Release preview when this composable leaves the composition
    DisposableEffect(Unit) {
        onDispose { cameraManager.stopPreview() }
    }
}

@Composable
private fun ConfirmationPreview(
    bitmap: Bitmap,
    onRetake: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(60.dp))
        
        Text(
            "Confirm Scene Photo",
            color      = Color.White,
            fontSize   = 20.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(Modifier.height(24.dp))

        // Center-cropped preview of the 768x768 bitmap
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 24.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
        ) {
            Image(
                bitmap             = bitmap.asImageBitmap(),
                contentDescription = "Captured frame",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(48.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Retake Button
            Button(
                onClick = onRetake,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.15f),
                    contentColor   = Color.White
                ),
                shape   = RoundedCornerShape(50),
                modifier = Modifier.height(56.dp).padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Retake", fontWeight = FontWeight.SemiBold)
            }

            // Use Photo Button
            Button(
                onClick = onConfirm,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32), // Green
                    contentColor   = Color.White
                ),
                shape   = RoundedCornerShape(50),
                modifier = Modifier.height(56.dp).padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Use Photo", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Gallery bitmap loading ─────────────────────────────────────────────────────

private suspend fun loadAndScaleBitmapFromUri(
    context: android.content.Context,
    uri: Uri,
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val raw: Bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return@withContext null

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

        val rotated = if (orientationDeg != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(orientationDeg) }
            android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                .also { if (it !== raw) raw.recycle() }
        } else {
            raw
        }

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
