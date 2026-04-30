package com.secondlife.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secondlife.audio.AudioCaptureManager
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
    viewModel: SecondLifeViewModel,
    ttsManager: TTSManager,
    audioCapture: AudioCaptureManager,
) {
    val response  by viewModel.response.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val scope      = rememberCoroutineScope()
    val context    = LocalContext.current
    var selectedRole by remember { mutableStateOf("layperson") }

    LaunchedEffect(response) {
        response?.let { ttsManager.speak(it.response) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("SecondLife") })
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                LargeFloatingActionButton(
                    onClick = {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            scope.launch {
                                @Suppress("MissingPermission")
                                val pcmBytes = audioCapture.captureUntilSilence()
                                // TODO(Rohan): replace placeholder with real STT transcript
                                val transcript = "How do I treat a severe burn?"
                                viewModel.query(text = transcript, audio = pcmBytes)
                            }
                        }
                    },
                    containerColor = if (isLoading)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Speak emergency",
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (isLoading) "Thinking…" else "Hold to speak clinical question",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Role selector ──────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ROLES.forEach { (roleKey, roleLabel) ->
                    FilterChip(
                        selected  = selectedRole == roleKey,
                        onClick   = {
                            selectedRole = roleKey
                            viewModel.setRole(roleKey)
                        },
                        label     = { Text(roleLabel, fontSize = 12.sp) },
                        modifier  = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Response card ──────────────────────────────────────────────
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
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> CircularProgressIndicator()
                        response != null -> Text(
                            text      = response!!.response,
                            fontSize  = 20.sp,
                            lineHeight = 28.sp,
                            textAlign = TextAlign.Start,
                            modifier  = Modifier.align(Alignment.TopStart)
                        )
                        else -> Text(
                            text      = "Ready to assist.\nAsk an emergency question.",
                            style     = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Citation + latency row ─────────────────────────────────────
            response?.let { r ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (r.citation.isNotBlank()) {
                        AssistChip(
                            onClick = {},
                            label   = { Text(r.citation, fontSize = 11.sp) },
                            colors  = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                labelColor     = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    Text(
                        text  = "~${r.latencyMs / 1000.0} s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}
