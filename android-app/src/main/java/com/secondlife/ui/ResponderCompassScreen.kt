package com.secondlife.ui

import androidx.compose.animation.core.animateFloatAsState
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
 * Full-screen compass screen shown to Person B (the helper) after they
 * accept the SOS. The arrow rotates to point toward Person A.
 *
 * arrowRotation = bearing to victim minus device heading (0 = straight ahead).
 * distanceMeters = null when GPS is unavailable (RSSI-only mode).
 */
@Composable
fun ResponderCompassScreen(
    arrowRotation: Float,         // degrees — 0 points straight up (toward victim)
    distanceMeters: Float?,       // null if GPS unavailable
    rssiLabel: String,            // "Close · ~10m" etc.
    isGpsAvailable: Boolean,
    assignedTask: String?,
    onArrived: () -> Unit,
) {
    // Smooth the arrow rotation so it doesn't snap
    val smoothRotation by animateFloatAsState(
        targetValue   = arrowRotation,
        animationSpec = tween(durationMillis = 300),
        label         = "compassArrow",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {

            Text(
                "NAVIGATE TO THEM",
                color      = Color(0xFFFF3B30),
                fontWeight = FontWeight.ExtraBold,
                fontSize   = 14.sp,
                letterSpacing = 3.sp,
            )

            // ── Compass arrow ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .background(Color(0xFF1C1C1E), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // Compass ring ticks (drawn behind the arrow)
                Canvas(modifier = Modifier.size(220.dp)) {
                    drawCompassRing(this)
                }
                // Animated arrow pointing toward the victim
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .rotate(smoothRotation),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawNavigationArrow(this)
                    }
                }
                // Dot at center
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(Color.White, CircleShape)
                )
            }

            // ── Distance display ──────────────────────────────────────────────
            if (isGpsAvailable && distanceMeters != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${distanceMeters.toInt()} m",
                        color      = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize   = 48.sp,
                    )
                    Text(
                        "away",
                        color    = Color(0xFF888888),
                        fontSize = 16.sp,
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        rssiLabel,
                        color      = Color(0xFF30D158),
                        fontWeight = FontWeight.Bold,
                        fontSize   = 22.sp,
                    )
                    Text(
                        "GPS unavailable · signal strength only",
                        color    = Color(0xFF555555),
                        fontSize = 12.sp,
                    )
                }
            }

            // ── Assigned task ─────────────────────────────────────────────────
            if (!assignedTask.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1C2B1C),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "YOUR TASK",
                            color      = Color(0xFF30D158),
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            assignedTask,
                            color    = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Text(
                "Keep walking in the direction the arrow points.\nThe arrow adjusts as you move.",
                color     = Color(0xFF555555),
                fontSize  = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )

            // ── Arrived button ────────────────────────────────────────────────
            Button(
                onClick = onArrived,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape  = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF30D158),
                    contentColor   = Color.Black,
                ),
            ) {
                Text("✅  I've Arrived — I'm with them", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

// ── Canvas helpers ─────────────────────────────────────────────────────────────

/** Draw 12 short tick marks around the compass ring. */
private fun drawCompassRing(scope: DrawScope) {
    val cx = scope.size.width / 2f
    val cy = scope.size.height / 2f
    val outerR = scope.size.width / 2f - 4f
    val innerR = outerR - 14f
    for (i in 0 until 12) {
        val angle = Math.toRadians((i * 30).toDouble())
        val cos = Math.cos(angle).toFloat()
        val sin = Math.sin(angle).toFloat()
        scope.drawLine(
            color = if (i == 0) Color(0xFFFF3B30) else Color(0xFF444444),
            start = androidx.compose.ui.geometry.Offset(cx + cos * innerR, cy + sin * innerR),
            end   = androidx.compose.ui.geometry.Offset(cx + cos * outerR, cy + sin * outerR),
            strokeWidth = if (i == 0) 4f else 2f,
        )
    }
}

/** Draw a bold upward-pointing arrow in red (north = toward victim). */
private fun drawNavigationArrow(scope: DrawScope) {
    val w = scope.size.width
    val h = scope.size.height
    val cx = w / 2f

    // Arrow head pointing UP
    val arrowPath = Path().apply {
        moveTo(cx, 6f)               // tip
        lineTo(cx + w * 0.28f, h * 0.50f)
        lineTo(cx + w * 0.12f, h * 0.50f)
        lineTo(cx + w * 0.12f, h - 6f)
        lineTo(cx - w * 0.12f, h - 6f)
        lineTo(cx - w * 0.12f, h * 0.50f)
        lineTo(cx - w * 0.28f, h * 0.50f)
        close()
    }
    scope.drawPath(arrowPath, color = Color(0xFFFF3B30))
}
