package com.secondlife.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secondlife.audio.SpeechManager
import com.secondlife.camera.CameraManager
import com.secondlife.inference.SecondLifeViewModel
import com.secondlife.tts.TTSManager
import kotlinx.coroutines.launch

private val ROLES = listOf(
    "layperson"      to "Layperson",
    "paramedic"      to "Paramedic",
    "military_medic" to "Military Medic",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel:     SecondLifeViewModel,
    ttsManager:    TTSManager,
    speechManager: SpeechManager,
    cameraManager: CameraManager,
) {
    val response      by viewModel.response.collectAsStateWithLifecycle()
    val isLoading     by viewModel.isLoading.collectAsStateWithLifecycle()
    val capturedImage by viewModel.capturedImage.collectAsStateWithLifecycle()
    val isListening   by speechManager.isListening.collectAsStateWithLifecycle()
    val partialText   by speechManager.partialText.collectAsStateWithLifecycle()
    val error         by viewModel.error.collectAsStateWithLifecycle()

    var selectedRole  by remember { mutableStateOf("layperson") }
    val scope         = rememberCoroutineScope()

    // Speak response aloud whenever it changes
    LaunchedEffect(response) {
        response?.let { ttsManager.speak(it.response) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("SecondLife") })
        },
        snackbarHost = {
            error?.let { msg ->
                Snackbar(
                    action = {
                        TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
                    }
                ) { Text(msg) }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // ── Role chips ────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ROLES.forEach { (key, label) ->
                    FilterChip(
                        selected = selectedRole == key,
                        onClick  = { selectedRole = key; viewModel.setRole(key) },
                        label    = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Response card ─────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isLoading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Thinking…", style = MaterialTheme.typography.bodyMedium)
                        }
                        isListening -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "🎤  Listening…",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (partialText.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    partialText,
                                    style     = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        response != null -> Text(
                            text       = response!!.response,
                            fontSize   = 20.sp,
                            lineHeight = 28.sp,
                            modifier   = Modifier.align(Alignment.TopStart),
                        )
                        else -> Text(
                            "Tap 📷 to capture the scene,\nthen tap 🎤 and describe the emergency.",
                            style     = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Citation + latency row ────────────────────────────────────
            response?.let { r ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (r.citation.isNotBlank()) {
                        AssistChip(
                            onClick = {},
                            label   = { Text(r.citation, fontSize = 11.sp) },
                            colors  = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                labelColor     = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    Text(
                        "~${r.latencyMs / 1000.0} s  ·  ${r.role}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Camera thumbnail + action row ─────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Thumbnail (shown when image is captured)
                CameraThumbnail(
                    bitmap   = capturedImage,
                    onClear  = { viewModel.clearCapturedImage() },
                    modifier = Modifier.size(72.dp),
                )

                Spacer(Modifier.weight(1f))

                // Camera button
                FilledTonalIconButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val bmp = cameraManager.captureFrame()
                                viewModel.setCapturedImage(bmp)
                            }.onFailure {
                                viewModel.postError("Camera failed: ${it.message}")
                            }
                        }
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = "Capture scene",
                        modifier = Modifier.size(28.dp),
                    )
                }

                // Mic FAB
                LargeFloatingActionButton(
                    onClick = { speechManager.toggle() },
                    containerColor = if (isListening)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop" else "Speak",
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraThumbnail(
    bitmap: Bitmap?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bitmap == null) {
        // Empty placeholder so layout doesn't jump
        Box(modifier.clip(RoundedCornerShape(8.dp)))
        return
    }

    Box(modifier) {
        Image(
            bitmap           = bitmap.asImageBitmap(),
            contentDescription = "Captured scene",
            contentScale     = ContentScale.Crop,
            modifier         = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp)),
        )
        // × button to clear
        IconButton(
            onClick  = onClear,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    shape = CircleShape,
                ),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Clear photo",
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
