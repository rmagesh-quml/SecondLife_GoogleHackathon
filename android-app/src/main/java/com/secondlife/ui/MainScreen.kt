package com.secondlife.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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

    var selectedRole by remember { mutableStateOf("layperson") }
    val scope        = rememberCoroutineScope()
    val scrollState  = rememberScrollState()

    // Scroll to top and speak whenever a new response arrives
    LaunchedEffect(response) {
        response?.let {
            scrollState.animateScrollTo(0)
            ttsManager.speak(it.response)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "SecondLife",
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    TextButton(onClick = { viewModel.newEmergency() }) {
                        Text(
                            "New",
                            color      = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            fontSize   = 14.sp,
                        )
                    }
                }
            )
        },
        snackbarHost = {
            error?.let { msg ->
                Snackbar(
                    action = {
                        TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
                    },
                    modifier = Modifier.padding(8.dp)
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

            // ── Role chips ────────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ROLES.forEach { (key, label) ->
                    FilterChip(
                        selected = selectedRole == key,
                        onClick  = { selectedRole = key; viewModel.setRole(key) },
                        label    = {
                            Text(
                                label,
                                fontSize   = 12.sp,
                                fontWeight = if (selectedRole == key) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Response card ─────────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors    = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Box(
                    modifier           = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment   = Alignment.Center,
                ) {
                    when {
                        isLoading -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(48.dp),
                                strokeWidth = 4.dp,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Analyzing emergency…",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (capturedImage != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Using your photo + voice",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        isListening -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "🎤  Listening…",
                                style      = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color      = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Describe the emergency clearly",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (partialText.isNotBlank()) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    partialText,
                                    style      = MaterialTheme.typography.bodyLarge,
                                    textAlign  = TextAlign.Center,
                                    fontWeight = FontWeight.Medium,
                                    color      = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        response != null -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text       = response!!.response,
                                fontSize   = 18.sp,
                                lineHeight = 28.sp,
                                color      = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        else -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "Ready for emergency",
                                style      = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color      = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "1. Tap 📷 to photograph the scene or injury\n" +
                                "2. Tap 🎤 and describe what's happening\n" +
                                "3. Get step-by-step guidance instantly",
                                style      = MaterialTheme.typography.bodyMedium,
                                textAlign  = TextAlign.Center,
                                lineHeight = 26.sp,
                                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Citation + latency ────────────────────────────────────────────
            response?.let { r ->
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
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

            // ── Bottom action row ─────────────────────────────────────────────
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CameraThumbnail(
                    bitmap   = capturedImage,
                    onClear  = { viewModel.clearCapturedImage() },
                    modifier = Modifier.size(72.dp),
                )

                Spacer(Modifier.weight(1f))

                // Camera button — turns teal/green when photo is staged
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                        colors   = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (capturedImage != null)
                                MaterialTheme.colorScheme.tertiaryContainer
                            else
                                MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector        = if (capturedImage != null)
                                Icons.Default.CheckCircle else Icons.Default.CameraAlt,
                            contentDescription = "Capture scene",
                            modifier           = Modifier.size(28.dp),
                            tint               = if (capturedImage != null)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Text(
                        text       = if (capturedImage != null) "Photo ✓" else "Photo",
                        style      = MaterialTheme.typography.labelSmall,
                        fontWeight = if (capturedImage != null) FontWeight.Bold else FontWeight.Normal,
                        color      = if (capturedImage != null)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Mic FAB — large, pulses when listening, red when active
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val fabScale by animateFloatAsState(
                        targetValue   = if (isListening) 1.1f else 1.0f,
                        animationSpec = tween(200),
                        label         = "fabScale"
                    )
                    LargeFloatingActionButton(
                        onClick        = { speechManager.toggle() },
                        containerColor = if (isListening)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary,
                        modifier       = Modifier.scale(fabScale),
                    ) {
                        Icon(
                            imageVector        = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isListening) "Stop" else "Speak emergency",
                            modifier           = Modifier.size(36.dp),
                            tint               = if (isListening)
                                MaterialTheme.colorScheme.onError
                            else
                                MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(
                        text       = if (isListening) "Tap to send" else "Speak",
                        style      = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color      = if (isListening)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
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
        Box(
            modifier         = modifier
                .clip(RoundedCornerShape(12.dp))
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("📷", fontSize = 24.sp)
        }
        return
    }

    Box(modifier) {
        Image(
            bitmap             = bitmap.asImageBitmap(),
            contentDescription = "Scene photo — included in analysis",
            contentScale       = ContentScale.Crop,
            modifier           = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = RoundedCornerShape(12.dp),
                ),
        )
        IconButton(
            onClick  = onClear,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .background(
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                    shape = CircleShape,
                ),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove photo",
                modifier           = Modifier.size(14.dp),
                tint               = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
