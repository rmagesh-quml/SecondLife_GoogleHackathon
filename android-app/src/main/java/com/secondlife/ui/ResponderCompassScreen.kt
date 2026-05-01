package com.secondlife.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen compass shown to Person B after they accept the SOS.
 *
 * Two modes:
 *  GPS mode   — arrow rotates to point exactly toward Person A using GPS bearing.
 *  RSSI mode  — GPS unavailable; shows a signal-strength finder instead.
 *               User walks until bars fill up (signal gets stronger = closer).
 *
 * arrowRotation = bearing-to-victim minus device-heading (0° = straight ahead).
 * distanceMeters = null when GPS is unavailable.
 */
@Composable
fun ResponderCompassScreen(
    arrowRotation: Float,
    distanceMeters: Float?,
    rssiLabel: String,
    isGpsAvailable: Boolean,
    assignedTask: String?,
    onArrived: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 56.dp, bottom = 32.dp),
        ) {
            // Header
            Text(
                if (isGpsAvailable) "NAVIGATE TO THEM" else "FIND THEM BY SIGNAL",
                color         = Color(0xFFFF3B30),
                fontWeight    = FontWeight.ExtraBold,
                fontSize      = 13.sp,
                letterSpacing = 3.sp,
            )

            // ── Main finder widget ────────────────────────────────────────────
            if (isGpsAvailable && distanceMeters != null) {
                GpsCompass(arrowRotation = arrowRotation, distanceMeters = distanceMeters)
            } else {
                RssiSignalFinder(rssiLabel = rssiLabel)
            }

            // ── Assigned task card ────────────────────────────────────────────
            if (!assignedTask.isNullOrBlank()) {
                Surface(
                    shape    = RoundedCornerShape(14.dp),
                    color    = Color(0xFF111E11),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "YOUR TASK",
                            color         = Color(0xFF30D158),
                            fontSize      = 11.sp,
                            fontWeight    = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            assignedTask,
                            color      = Color.White,
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // ── Arrived button ────────────────────────────────────────────────
            Button(
                onClick  = onArrived,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape  = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1DB954),
                    contentColor   = Color.Black,
                ),
            ) {
                Text("✅  I've Arrived — I'm with them", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

// ── GPS Compass (directional arrow) ───────────────────────────────────────────

@Composable
private fun GpsCompass(arrowRotation: Float, distanceMeters: Float) {
    val smoothRotation by animateFloatAsState(
        targetValue   = arrowRotation,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label         = "compassArrow",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Compass disc
        Box(
            modifier = Modifier
                .size(250.dp)
                .background(Color(0xFF141414), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Tick ring
            Canvas(modifier = Modifier.size(230.dp)) { drawCompassRing(this) }

            // Animated arrow
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .rotate(smoothRotation),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) { drawNavigationArrow(this) }
            }

            // Centre dot
            Box(Modifier.size(14.dp).background(Color.White, CircleShape))
        }

        // Distance readout
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${distanceMeters.toInt()} m",
                color      = Color.White,
                fontWeight = FontWeight.Black,
                fontSize   = 52.sp,
            )
            Text("away", color = Color(0xFF666666), fontSize = 15.sp)
        }

        Text(
            "Keep the arrow pointing up.\nWalk straight ahead.",
            color      = Color(0xFF444444),
            fontSize   = 12.sp,
            textAlign  = TextAlign.Center,
            lineHeight = 18.sp,
        )
    }
}

// ── RSSI Signal Finder (no GPS) ───────────────────────────────────────────────

@Composable
private fun RssiSignalFinder(rssiLabel: String) {
    val strength = rssiLabelToStrength(rssiLabel)
    val inf      = rememberInfiniteTransition(label = "rssi")

    // Pulse intensity scales with signal strength — faster pulse = stronger signal
    val pulseDuration = when (strength) {
        5    -> 350
        4    -> 500
        3    -> 700
        2    -> 950
        1    -> 1200
        else -> 1600
    }
    val pulse by inf.animateFloat(
        initialValue  = 0.85f,
        targetValue   = 1.12f,
        animationSpec = infiniteRepeatable(
            tween(pulseDuration, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "signalPulse",
    )

    // Color shifts from dim red → bright green as signal gets stronger
    val signalColor = when (strength) {
        5    -> Color(0xFF00E676)   // bright green — very close
        4    -> Color(0xFF30D158)   // green
        3    -> Color(0xFFFFCC00)   // amber
        2    -> Color(0xFFFF9500)   // orange
        1    -> Color(0xFFFF3B30)   // red — far
        else -> Color(0xFF444444)   // grey — no signal
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Pulsing signal disc
        Box(
            modifier         = Modifier.size(250.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Outer glow ring
            Box(
                Modifier
                    .size((200 * pulse).dp)
                    .background(signalColor.copy(alpha = 0.12f), CircleShape)
            )
            // Inner ring
            Box(
                Modifier
                    .size(160.dp)
                    .background(signalColor.copy(alpha = 0.18f), CircleShape)
            )
            // Core disc
            Box(
                Modifier
                    .size(110.dp)
                    .background(Color(0xFF141414), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (strength) {
                        5, 4 -> "📡"
                        3    -> "📶"
                        2, 1 -> "🔍"
                        else -> "⏳"
                    },
                    fontSize = 40.sp,
                )
            }
        }

        // Large signal bars
        SignalBars(strength = strength, barColor = signalColor)

        // Distance label — big and clear
        Text(
            rssiLabel,
            color      = signalColor,
            fontWeight = FontWeight.ExtraBold,
            fontSize   = 24.sp,
            textAlign  = TextAlign.Center,
        )

        // Instructions
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF151515),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "HOW TO FIND THEM",
                    color         = Color(0xFF888888),
                    fontSize      = 10.sp,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                InstructionRow("1", "Walk in any direction")
                InstructionRow("2", "Watch the bars — if they drop, turn around")
                InstructionRow("3", "Keep walking toward stronger signal")
                InstructionRow("4", "Very Close = you're there")
            }
        }
    }
}

@Composable
private fun InstructionRow(step: String, text: String) {
    Row(
        verticalAlignment   = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(Color(0xFF222222), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(step, color = Color(0xFF888888), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = Color(0xFFCCCCCC), fontSize = 14.sp)
    }
}

// ── Canvas drawing helpers ────────────────────────────────────────────────────

private fun drawCompassRing(scope: DrawScope) {
    val cx = scope.size.width / 2f
    val cy = scope.size.height / 2f
    val outerR = scope.size.width / 2f - 4f
    val innerR = outerR - 16f
    for (i in 0 until 12) {
        val angle = Math.toRadians((i * 30).toDouble())
        val cos = Math.cos(angle).toFloat()
        val sin = Math.sin(angle).toFloat()
        scope.drawLine(
            color       = if (i == 0) Color(0xFFFF3B30) else Color(0xFF333333),
            start       = androidx.compose.ui.geometry.Offset(cx + cos * innerR, cy + sin * innerR),
            end         = androidx.compose.ui.geometry.Offset(cx + cos * outerR, cy + sin * outerR),
            strokeWidth = if (i == 0) 5f else 2f,
        )
    }
}

private fun drawNavigationArrow(scope: DrawScope) {
    val w  = scope.size.width
    val h  = scope.size.height
    val cx = w / 2f
    val path = Path().apply {
        moveTo(cx, 4f)
        lineTo(cx + w * 0.28f, h * 0.50f)
        lineTo(cx + w * 0.12f, h * 0.50f)
        lineTo(cx + w * 0.12f, h - 4f)
        lineTo(cx - w * 0.12f, h - 4f)
        lineTo(cx - w * 0.12f, h * 0.50f)
        lineTo(cx - w * 0.28f, h * 0.50f)
        close()
    }
    scope.drawPath(path, color = Color(0xFFFF3B30))
}
