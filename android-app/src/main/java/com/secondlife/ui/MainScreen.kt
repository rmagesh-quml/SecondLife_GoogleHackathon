package com.secondlife.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secondlife.audio.SpeechManager
import com.secondlife.camera.CameraManager
import com.secondlife.emergency.EmergencyTimerManager
import com.secondlife.inference.EmergencySession
import com.secondlife.inference.ResponseMode
import com.secondlife.inference.SecondLifeResponse
import com.secondlife.inference.SecondLifeViewModel
import com.secondlife.inference.TranscriptTurn
import com.secondlife.tts.TTSManager
import com.secondlife.ui.theme.LocalSecondLifeColors
import com.secondlife.ui.theme.colorForRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.sin

// ── Roles & helpers ─────────────────────────────────────────────────────────
// Source-of-truth for role keys/labels. UI never hardcodes these strings.
internal val ROLES: List<Pair<String, String>> = listOf(
    "layperson"      to "Layperson",
    "paramedic"      to "Paramedic",
    "military_medic" to "Military Medic",
)

internal fun labelForRole(key: String): String =
    ROLES.firstOrNull { it.first == key }?.second ?: key

// ── Stateful entry point ────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel:     SecondLifeViewModel,
    ttsManager:    TTSManager,
    speechManager: SpeechManager,
    cameraManager: CameraManager,
) {
    val response       by viewModel.response.collectAsStateWithLifecycle()
    val isThinking     by viewModel.isLoading.collectAsStateWithLifecycle()
    val capturedImage  by viewModel.capturedImage.collectAsStateWithLifecycle()
    val transcript     by viewModel.transcript.collectAsStateWithLifecycle()
    val role           by viewModel.role.collectAsStateWithLifecycle()
    val sessionStarted by viewModel.sessionStartedAt.collectAsStateWithLifecycle()
    val error          by viewModel.error.collectAsStateWithLifecycle()
    val sessions       by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSession  by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val isListening    by speechManager.isListening.collectAsStateWithLifecycle()
    val partialText    by speechManager.partialText.collectAsStateWithLifecycle()
    val rmsLevel       by speechManager.rmsLevel.collectAsStateWithLifecycle()
    val isSpeaking     by ttsManager.isSpeaking.collectAsStateWithLifecycle()
    val modelReady     by viewModel.modelReady.collectAsStateWithLifecycle()
    val streamingText  by viewModel.streamingText.collectAsStateWithLifecycle()
    val timerState     by viewModel.timerState.collectAsStateWithLifecycle()
    val metronomeBeat  by viewModel.metronomeBeat.collectAsStateWithLifecycle()
    val handoffReport  by viewModel.handoffReport.collectAsStateWithLifecycle()
    var selectedMode   by remember { mutableStateOf(ResponseMode.DETAIL) }

    // Tick once per second to update the session timer without recomposing
    // the entire tree on every frame.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sessionStarted) {
        while (sessionStarted != null) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = sessionStarted?.let { nowMs - it }

    // Speak each new completed response aloud
    LaunchedEffect(response) {
        response?.response?.takeIf { it.isNotBlank() }?.let { ttsManager.speak(it) }
    }

    val state = MainUiState(
        role              = role,
        isOnDevice        = modelReady,
        signalLabel       = if (modelReady) "Ready" else "Loading…",
        sessionElapsedMs  = elapsed,
        rmsLevel          = rmsLevel,
        isListening       = isListening,
        isThinking        = isThinking,
        isSpeaking        = isSpeaking,
        partialTranscript = partialText,
        transcript        = transcript,
        capturedImage     = capturedImage,
        error             = error,
        sessions          = sessions,
        activeSessionId   = activeSession,
        streamingText     = streamingText,
        timerState        = timerState,
        metronomeBeat     = metronomeBeat,
        handoffReport     = handoffReport,
        selectedMode      = selectedMode,
    )

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope       = rememberCoroutineScope()

    val callbacks = MainUiCallbacks(
        onMicTap     = { speechManager.toggle() },
        onCameraTap  = {
            scope.launch {
                runCatching { cameraManager.captureFrame() }
                    .onSuccess { viewModel.setCapturedImage(it) }
                    .onFailure { viewModel.postError("Camera failed: ${it.message}") }
            }
        },
        onCancelTap     = {
            ttsManager.stop()
            speechManager.stop()
            viewModel.cancelSession()
        },
        onRoleChange    = viewModel::setRole,
        onClearImage    = viewModel::clearCapturedImage,
        onErrorDismiss  = viewModel::dismissError,

        onMenuOpen      = { scope.launch { drawerState.open() } },
        onNewSession    = {
            viewModel.newSession()
            scope.launch { drawerState.close() }
        },
        onSelectSession = { id ->
            viewModel.selectSession(id)
            scope.launch { drawerState.close() }
        },
        onDeleteSession    = viewModel::deleteSession,
        onModeChange       = { mode -> selectedMode = mode; viewModel.setMode(mode) },
        onStopTimer        = { viewModel.stopTimer(); viewModel.stopMetronome() },
        onResetTimer       = { viewModel.resetTimer(); viewModel.stopMetronome() },
        onGenerateReport   = viewModel::generateHandoffReport,
        onDismissReport    = viewModel::dismissHandoffReport,
    )

    // Handoff report dialog
    state.handoffReport?.let { report ->
        AlertDialog(
            onDismissRequest = callbacks.onDismissReport,
            title = { Text("Handoff Report") },
            text  = {
                val scrollState = rememberScrollState()
                Text(
                    text     = report,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.verticalScroll(scrollState),
                )
            },
            confirmButton = {
                Button(onClick = callbacks.onDismissReport) { Text("Close") }
            },
        )
    }

    ModalNavigationDrawer(
        drawerState   = drawerState,
        drawerContent = { EmergencySessionsDrawer(state, callbacks) },
    ) {
        MainScreenContent(state = state, callbacks = callbacks)
    }
}

// ── Pure presentational layer (preview-friendly) ────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    state:     MainUiState,
    callbacks: MainUiCallbacks,
    modifier:  Modifier = Modifier,
) {
    Scaffold(
        modifier        = modifier,
        containerColor  = MaterialTheme.colorScheme.background,
        snackbarHost = {
            state.error?.let { msg ->
                Snackbar(
                    action = { TextButton(onClick = callbacks.onErrorDismiss) { Text("OK") } }
                ) { Text(msg) }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {

            val listState = rememberLazyListState()
            // Auto-scroll to the newest item whenever the transcript or partial text changes.
            val scrollTarget = state.transcript.size + if (state.partialTranscript.isNotBlank() && state.isListening) 1 else 0
            LaunchedEffect(scrollTarget) {
                if (scrollTarget > 0) listState.animateScrollToItem(scrollTarget - 1)
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(12.dp))

                StatusRow(
                    isOnDevice     = state.isOnDevice,
                    signalLabel    = state.signalLabel,
                    role           = state.role,
                    sessionCount   = state.sessions.size,
                    onMenuClick    = callbacks.onMenuOpen,
                    onRoleSelected = callbacks.onRoleChange,
                )

                Spacer(Modifier.height(20.dp))

                SessionHeader(elapsedMs = state.sessionElapsedMs)

                Spacer(Modifier.height(12.dp))

                WaveformVisualizer(
                    rmsLevel    = state.rmsLevel,
                    isActive    = state.isListening || state.isSpeaking,
                    accentColor = if (state.isListening)
                        LocalSecondLifeColors.current.accentGreen
                    else
                        LocalSecondLifeColors.current.accentBlue,
                    modifier    = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                )

                Spacer(Modifier.height(12.dp))

                StatusIndicator(
                    label = state.statusLabel,
                    color = indicatorColor(state),
                )

                Spacer(Modifier.height(20.dp))

                // Conversation thread — all turns in chronological order.
                // Bottom padding clears the floating action bar (~110 dp).
                LazyColumn(
                    state               = listState,
                    modifier            = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding      = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.transcript, key = { it.id }) { turn ->
                        val isLatest  = turn.id == state.transcript.lastOrNull()?.id
                        TranscriptTurnView(
                            turn      = turn,
                            role      = state.role,
                            isLatest  = isLatest,
                            isLoading = isLatest && state.isThinking,
                        )
                    }
                    // Partial-transcript card appended live while the user speaks.
                    if (state.partialTranscript.isNotBlank() && state.isListening) {
                        item("partial") {
                            YouSaidCard(
                                text      = state.partialTranscript,
                                timestamp = null,
                                isPartial = true,
                            )
                        }
                    }
                }
            }

            // Timer / mode row above the action bar
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 106.dp)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.timerState?.let { timer ->
                    TimerRow(
                        timerState    = timer,
                        metronomeBeat = state.metronomeBeat,
                        onStop        = callbacks.onStopTimer,
                        onReset       = callbacks.onResetTimer,
                    )
                }
                ModeToggleRow(
                    selectedMode     = state.selectedMode,
                    hasResponse      = state.latestResponse != null,
                    onModeChange     = callbacks.onModeChange,
                    onGenerateReport = callbacks.onGenerateReport,
                )
            }

            // Floating action bar pinned to the bottom of the screen.
            BottomActionBar(
                isListening   = state.isListening,
                capturedImage = state.capturedImage,
                onCancelTap   = callbacks.onCancelTap,
                onMicTap      = callbacks.onMicTap,
                onCameraTap   = callbacks.onCameraTap,
                onClearImage  = callbacks.onClearImage,
                modifier      = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun indicatorColor(state: MainUiState): Color {
    val ext = LocalSecondLifeColors.current
    return when {
        state.error != null -> ext.accentRed
        state.isListening   -> ext.accentGreen
        state.isThinking    -> ext.accentBlue
        state.isSpeaking    -> ext.accentBlue
        else                -> ext.textMuted
    }
}

// ── Status row (top) ────────────────────────────────────────────────────────
@Composable
private fun StatusRow(
    isOnDevice:     Boolean,
    signalLabel:    String,
    role:           String,
    sessionCount:   Int,
    onMenuClick:    () -> Unit,
    onRoleSelected: (String) -> Unit,
) {
    val ext = LocalSecondLifeColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left cluster: hamburger + on-device pill
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onMenuClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Open emergencies",
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(20.dp),
                )
                if (sessionCount > 1) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(ext.accentGreen)
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isOnDevice) ext.accentGreen else ext.textMuted)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text  = if (isOnDevice) "On-device · $signalLabel" else signalLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Right: role badge with dropdown
        RoleSelector(role = role, onRoleSelected = onRoleSelected)
    }
}

@Composable
private fun RoleSelector(
    role:           String,
    onRoleSelected: (String) -> Unit,
) {
    val ext     = LocalSecondLifeColors.current
    val accent  = ext.colorForRole(role)
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.15f))
                .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(50))
                .clickable { expanded = true }
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text       = labelForRole(role).uppercase(Locale.US),
                color      = accent,
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector        = Icons.Default.ArrowDropDown,
                contentDescription = "Choose role",
                tint               = accent,
                modifier           = Modifier.size(18.dp),
            )
        }

        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
            modifier         = Modifier.background(MaterialTheme.colorScheme.surface),
        ) {
            ROLES.forEach { (key, label) ->
                val itemAccent = ext.colorForRole(key)
                val isCurrent  = key == role
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(itemAccent)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text       = label,
                                color      = if (isCurrent) itemAccent
                                             else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isCurrent) FontWeight.SemiBold
                                             else FontWeight.Normal,
                                style      = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (!isCurrent) onRoleSelected(key)
                    },
                )
            }
        }
    }
}

// ── Session header (timer) ──────────────────────────────────────────────────
@Composable
private fun SessionHeader(elapsedMs: Long?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text  = formatElapsed(elapsedMs),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text  = if (elapsedMs == null) "Tap mic to begin"
                    else "Session active".uppercase(Locale.US),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatElapsed(ms: Long?): String {
    if (ms == null) return "00:00"
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

// ── Waveform visualiser ─────────────────────────────────────────────────────
@Composable
private fun WaveformVisualizer(
    rmsLevel: Float,
    isActive: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 28,
) {
    val target by animateFloatAsState(
        targetValue = if (isActive) rmsLevel.coerceIn(0f, 1f) else 0.05f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "rms",
    )
    val transition = rememberInfiniteTransition(label = "wave-phase")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue  = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val density = LocalDensity.current

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barWidthPx = with(density) { 3.dp.toPx() }
        val gapPx      = with(density) { 5.dp.toPx() }
        val totalW     = barCount * barWidthPx + (barCount - 1) * gapPx
        val startX     = (w - totalW) / 2f
        val centerY    = h / 2f

        for (i in 0 until barCount) {
            val t        = i.toFloat() / (barCount - 1)
            val envelope = 1f - ((t - 0.5f) * 2f).absoluteValue.let { it * it }
            val wave     = (sin(phase + t * 6f) + 1f) / 2f
            val mag      = (0.15f + 0.85f * target) * (0.4f + 0.6f * wave) * envelope
            val barH     = (mag * h * 0.9f).coerceAtLeast(barWidthPx)

            val x = startX + i * (barWidthPx + gapPx)
            drawRoundRect(
                brush       = SolidColor(accentColor.copy(alpha = 0.65f + 0.35f * envelope)),
                topLeft     = androidx.compose.ui.geometry.Offset(x, centerY - barH / 2f),
                size        = androidx.compose.ui.geometry.Size(barWidthPx, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidthPx / 2f),
            )
        }
    }
}

// ── Status indicator (dot + label) ──────────────────────────────────────────
@Composable
private fun StatusIndicator(label: String, color: Color) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── Transcript: one turn ────────────────────────────────────────────────────
@Composable
private fun TranscriptTurnView(
    turn:      TranscriptTurn,
    role:      String,
    isLatest:  Boolean = false,
    isLoading: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        YouSaidCard(text = turn.userText, timestamp = turn.createdAt)
        when {
            turn.response != null -> ProtocolCard(response = turn.response, role = role, isActive = isLatest)
            isLoading             -> ThinkingRow()
        }
    }
}

@Composable
private fun ThinkingRow() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue  = 0.35f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label         = "dot-alpha",
    )
    val ext = LocalSecondLifeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "THINKING",
            style = MaterialTheme.typography.labelMedium,
            color = ext.accentBlue.copy(alpha = alpha),
        )
        Spacer(Modifier.width(10.dp))
        repeat(3) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(ext.accentBlue.copy(alpha = alpha))
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun YouSaidCard(text: String, timestamp: Long?, isPartial: Boolean = false) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (isPartial) 0.6f else 1f)
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text  = if (isPartial) "Listening".uppercase(Locale.US)
                            else "You said".uppercase(Locale.US),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                timestamp?.let {
                    Text(
                        text  = SimpleDateFormat("HH:mm", Locale.US).format(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalSecondLifeColors.current.textMuted,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text  = "\"" + text + "\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ProtocolCard(response: SecondLifeResponse, role: String, isActive: Boolean = false) {
    val ext    = LocalSecondLifeColors.current
    val accent = ext.colorForRole(role)
    val title  = protocolTitleFromCitation(response.citation)
    val source = sourceLabelFromCitation(response.citation)
    val steps  = response.steps.ifEmpty { parseNumberedSteps(response.response) }
    val borderAlpha = if (isActive) 0.6f else 0.2f

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape  = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = borderAlpha)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text  = title.uppercase(Locale.US),
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (source.isNotBlank()) {
                    CitationChip(label = source)
                }
            }
            Spacer(Modifier.height(12.dp))

            if (steps.isNotEmpty()) {
                steps.forEachIndexed { index, step ->
                    StepRow(index = index + 1, text = step, accent = accent)
                    if (index < steps.lastIndex) Spacer(Modifier.height(8.dp))
                }
            } else {
                Text(
                    text  = highlightKeyTerms(response.response),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text  = "%dms · %s".format(response.latencyMs, response.role),
                style = MaterialTheme.typography.labelSmall,
                color = ext.textMuted,
            )
        }
    }
}

@Composable
private fun StepRow(index: Int, text: String, accent: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = index.toString(),
                color = accent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text  = highlightKeyTerms(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun CitationChip(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text  = "⏱  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Timer row ───────────────────────────────────────────────────────────────
@Composable
private fun TimerRow(
    timerState:    com.secondlife.emergency.TimerState,
    metronomeBeat: Boolean,
    onStop:        () -> Unit,
    onReset:       () -> Unit,
) {
    val ext = LocalSecondLifeColors.current
    val pulseAlpha by animateFloatAsState(
        targetValue   = if (metronomeBeat) 1f else 0.4f,
        animationSpec = tween(150),
        label         = "metronome-pulse",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, ext.accentRed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(ext.accentRed.copy(alpha = if (metronomeBeat) pulseAlpha else 0.8f))
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text  = timerState.label.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = ext.textMuted,
                )
                Text(
                    text  = EmergencyTimerManager.formatElapsed(timerState.elapsedMs),
                    style = MaterialTheme.typography.titleMedium,
                    color = ext.accentRed,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onReset) { Text("Reset", color = ext.textMuted, style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = onStop)  { Text("Stop",  color = ext.accentRed,  style = MaterialTheme.typography.labelMedium) }
        }
    }
}

// ── Mode toggle + handoff report trigger ────────────────────────────────────
@Composable
private fun ModeToggleRow(
    selectedMode:     com.secondlife.inference.ResponseMode,
    hasResponse:      Boolean,
    onModeChange:     (com.secondlife.inference.ResponseMode) -> Unit,
    onGenerateReport: () -> Unit,
) {
    val ext = LocalSecondLifeColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.secondlife.inference.ResponseMode.entries.forEach { mode ->
                val selected = mode == selectedMode
                FilterChip(
                    selected = selected,
                    onClick  = { onModeChange(mode) },
                    label    = {
                        Text(
                            text  = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }
        if (hasResponse) {
            TextButton(onClick = onGenerateReport) {
                Text("Report", color = ext.accentBlue, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ── Bottom action bar ───────────────────────────────────────────────────────
@Composable
private fun BottomActionBar(
    isListening:   Boolean,
    capturedImage: Bitmap?,
    onCancelTap:   () -> Unit,
    onMicTap:      () -> Unit,
    onCameraTap:   () -> Unit,
    onClearImage:  () -> Unit,
    modifier:      Modifier = Modifier,
) {
    val ext = LocalSecondLifeColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        AnimatedVisibility(visible = capturedImage != null) {
            CapturedImageThumbnail(
                bitmap   = capturedImage,
                onClear  = onClearImage,
                modifier = Modifier.padding(bottom = 12.dp).size(72.dp),
            )
        }
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CircleActionButton(
                icon            = Icons.Default.Close,
                contentDesc     = "Cancel",
                size            = 56.dp,
                background      = ext.accentRed.copy(alpha = 0.15f),
                iconColor       = ext.accentRed,
                onClick         = onCancelTap,
            )
            CircleActionButton(
                icon            = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDesc     = if (isListening) "Stop" else "Speak",
                size            = 76.dp,
                background      = ext.accentGreen,
                iconColor       = Color(0xFF06241A),
                onClick         = onMicTap,
            )
            CircleActionButton(
                icon            = Icons.Default.CameraAlt,
                contentDesc     = "Capture scene",
                size            = 56.dp,
                background      = ext.accentBlue.copy(alpha = 0.15f),
                iconColor       = ext.accentBlue,
                onClick         = onCameraTap,
            )
        }
    }
}

@Composable
private fun CircleActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    size: androidx.compose.ui.unit.Dp,
    background: Color,
    iconColor: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDesc,
            tint               = iconColor,
            modifier           = Modifier.size(size * 0.42f),
        )
    }
}

@Composable
private fun CapturedImageThumbnail(
    bitmap:   Bitmap?,
    onClear:  () -> Unit,
    modifier: Modifier = Modifier,
) {
    bitmap ?: return
    Box(modifier) {
        Image(
            bitmap             = bitmap.asImageBitmap(),
            contentDescription = "Captured scene",
            contentScale       = ContentScale.Crop,
            modifier           = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        )
        IconButton(
            onClick  = onClear,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Clear photo",
                modifier           = Modifier.size(14.dp),
                tint               = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── Text helpers ────────────────────────────────────────────────────────────
@Composable
internal fun highlightKeyTerms(raw: String): AnnotatedString {
    val accent = LocalSecondLifeColors.current.accentBlue
    val regex  = Regex("""\*\*(.+?)\*\*""")
    return buildAnnotatedString {
        var lastEnd = 0
        for (match in regex.findAll(raw)) {
            append(raw.substring(lastEnd, match.range.first))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                append(match.groupValues[1])
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < raw.length) append(raw.substring(lastEnd))
    }
}

internal fun protocolTitleFromCitation(citation: String): String {
    val source = citation.substringBefore(",").trim()
    if (source.isBlank()) return "Protocol"
    return source
        .substringBeforeLast('.')
        .replace('_', ' ')
        .replace('-', ' ')
        .split(' ')
        .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
}

internal fun sourceLabelFromCitation(citation: String): String {
    val tail = citation.substringAfter(",", "").trim()
    return tail.ifBlank { citation }
}

// ── Side drawer: list of emergency sessions ────────────────────────────────
@Composable
fun EmergencySessionsDrawer(
    state:     MainUiState,
    callbacks: MainUiCallbacks,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier             = Modifier,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = "Emergencies",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(LocalSecondLifeColors.current.accentGreen.copy(alpha = 0.18f))
                        .clickable(onClick = callbacks.onNewSession),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New emergency",
                        tint               = LocalSecondLifeColors.current.accentGreen,
                        modifier           = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text  = "%d active session%s".format(
                    state.sessions.size,
                    if (state.sessions.size == 1) "" else "s",
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.sessions, key = { it.id }) { sess ->
                    SessionRow(
                        session  = sess,
                        isActive = sess.id == state.activeSessionId,
                        onSelect = { callbacks.onSelectSession(sess.id) },
                        onDelete = { callbacks.onDeleteSession(sess.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session:  EmergencySession,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val ext    = LocalSecondLifeColors.current
    val accent = ext.colorForRole(session.role)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) accent.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = 1.dp,
                color = if (isActive) accent.copy(alpha = 0.45f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text       = session.title,
                maxLines   = 1,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color      = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = sessionSubtitle(session, isActive),
                style = MaterialTheme.typography.labelSmall,
                color = ext.textMuted,
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete session",
                tint               = ext.textMuted,
                modifier           = Modifier.size(14.dp),
            )
        }
    }
}

private fun sessionSubtitle(session: EmergencySession, isActive: Boolean): String {
    val turns = session.transcript.size
    val turnLabel = when (turns) {
        0    -> "No turns yet"
        1    -> "1 turn"
        else -> "$turns turns"
    }
    val ageLabel = formatRelativeAge(session.createdAt)
    return when {
        isActive -> "Active now · $turnLabel"
        else     -> "$turnLabel · $ageLabel"
    }
}

private fun formatRelativeAge(thenMs: Long): String {
    val deltaMs = (System.currentTimeMillis() - thenMs).coerceAtLeast(0)
    val mins = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
    val hrs  = TimeUnit.MILLISECONDS.toHours(deltaMs)
    return when {
        mins < 1   -> "just now"
        mins < 60  -> "${mins}m ago"
        hrs  < 24  -> "${hrs}h ago"
        else       -> "${hrs / 24}d ago"
    }
}

internal fun parseNumberedSteps(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val markerRegex = Regex("""(?m)^\s*(?:Step\s+)?(\d+)[.\):\-]\s+""")
    val matches = markerRegex.findAll(text).toList()
    if (matches.size < 2) return emptyList()

    return matches.mapIndexed { i, m ->
        val end = if (i == matches.lastIndex) text.length else matches[i + 1].range.first
        text.substring(m.range.last + 1, end).trim().replace("\n", " ")
    }
}
