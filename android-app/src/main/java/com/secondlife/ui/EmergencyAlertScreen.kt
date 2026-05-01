package com.secondlife.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secondlife.mesh.MeshManager

/**
 * Full-screen takeover alert for Person B when a nearby SOS is detected.
 *
 * Design goals:
 *  • Can't be missed — full screen, no card, strobing red background
 *  • Works in direct sunlight on an S25 Ultra — very high contrast
 *  • Accept button is enormous — easy to tap when panicking
 */
@Composable
fun EmergencyAlertScreen(
    broadcast: MeshManager.EmergencyBroadcast,
    rssiLabel: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val inf = rememberInfiniteTransition(label = "alert")

    // Strobe: background pulses between almost-black and deep red
    val bgAlpha by inf.animateFloat(
        initialValue  = 0.08f,
        targetValue   = 0.28f,
        animationSpec = infiniteRepeatable(
            tween(600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "bgStrobe",
    )

    // SOS ring: slow breathe
    val ringScale by inf.animateFloat(
        initialValue  = 1f,
        targetValue   = 1.35f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "ring",
    )

    // Outer strobe ring
    val outerScale by inf.animateFloat(
        initialValue  = 1f,
        targetValue   = 1.65f,
        animationSpec = infiniteRepeatable(
            tween(1200, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "outerRing",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF1A0000).copy(alpha = 1f),
                        Color(0xFF0A0000),
                    )
                )
            )
            // Red strobe overlay
            .background(Color(0xFFFF0000).copy(alpha = bgAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 64.dp, bottom = 32.dp),
        ) {

            // ── TOP SECTION: Icon + title ─────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {

                // Triple-ring pulsing icon
                Box(contentAlignment = Alignment.Center) {
                    // Outer slow ring
                    Box(
                        Modifier
                            .size(160.dp)
                            .scale(outerScale)
                            .background(Color(0xFFFF0000).copy(alpha = 0.10f), CircleShape)
                    )
                    // Middle ring
                    Box(
                        Modifier
                            .size(120.dp)
                            .scale(ringScale)
                            .background(Color(0xFFFF0000).copy(alpha = 0.22f), CircleShape)
                    )
                    // Solid core
                    Box(
                        Modifier
                            .size(88.dp)
                            .background(Color(0xFFFF1A00), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("🚨", fontSize = 40.sp)
                    }
                }

                Text(
                    "EMERGENCY NEARBY",
                    color         = Color(0xFFFF3B30),
                    fontWeight    = FontWeight.Black,
                    fontSize      = 26.sp,
                    letterSpacing = 3.sp,
                    textAlign     = TextAlign.Center,
                )

                // Emergency type badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF3A0A0A),
                ) {
                    Text(
                        broadcast.type.replace("_", " ").uppercase(),
                        modifier      = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        color         = Color(0xFFFF6B6B),
                        fontWeight    = FontWeight.ExtraBold,
                        fontSize      = 15.sp,
                        letterSpacing = 2.sp,
                    )
                }
            }

            // ── MIDDLE SECTION: What happened + signal ────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    broadcast.summary,
                    color      = Color.White,
                    fontSize   = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    lineHeight = 30.sp,
                )

                // Signal strength with bars
                val strength = rssiLabelToStrength(rssiLabel)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SignalBars(strength = strength, barColor = Color(0xFF30D158))
                    Text(
                        rssiLabel,
                        color      = Color(0xFF30D158),
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Move toward stronger signal to navigate",
                        color    = Color(0xFF777777),
                        fontSize = 13.sp,
                    )
                }
            }

            // ── BOTTOM SECTION: Action buttons ────────────────────────────────
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick  = onAccept,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp),
                    shape  = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1DB954),
                        contentColor   = Color.Black,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
                ) {
                    Text(
                        "🧭  I'll Help — Find Them",
                        fontWeight = FontWeight.Black,
                        fontSize   = 18.sp,
                    )
                }

                TextButton(onClick = onDismiss) {
                    Text(
                        "I cannot help right now",
                        color    = Color(0xFF555555),
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

/** Convert RSSI label to 0-5 bar count. */
internal fun rssiLabelToStrength(label: String): Int = when {
    label.startsWith("Very close") -> 5
    label.startsWith("Close")      -> 4
    label.startsWith("Nearby")     -> 3
    label.startsWith("In range")   -> 2
    label.startsWith("Far")        -> 1
    else                           -> 0
}

/** WiFi-style signal strength bars. */
@Composable
fun SignalBars(strength: Int, barColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier            = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment   = Alignment.Bottom,
    ) {
        for (i in 1..5) {
            val filled = i <= strength
            val barHeight = (8 + i * 8).dp
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(barHeight)
                    .background(
                        if (filled) barColor else barColor.copy(alpha = 0.18f),
                        RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                    )
            )
        }
    }
}
