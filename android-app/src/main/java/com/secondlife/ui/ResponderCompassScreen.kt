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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen "Warm/Cool" responder interface.
 * The background gradient and central glow shift from Blue (Cold) to Red (Hot)
 * as the responder gets closer to the victim.
 */
private val COLD_BG = Color(0xFF06111E)
private val HOT_BG  = Color(0xFF3D0508)

@Composable
fun ResponderCompassScreen(
    arrowRotation: Float,
    distanceMeters: Float?,
    rssiLabel: String,
    isGpsAvailable: Boolean,
    assignedTask: String?,
    onArrived: () -> Unit,
) {
    // 0 = none, 5 = very close
    val rssiStrength = rssiLabelToStrength(rssiLabel)

    // 0.0 = far/cold   →   1.0 = close/hot
    val targetWarmth = when {
        // If Bluetooth says we are "Very close", trust it over GPS 
        // (GPS is often inaccurate or stale indoors)
        rssiStrength >= 5 -> 1.0f
        
        distanceMeters != null && distanceMeters <= 5f -> 1.0f
        
        // RELAXED FOR DEMO: Allow GPS to drive warmth if distance is plausible
        distanceMeters != null && distanceMeters < 1000f ->
            (1f - (distanceMeters / 150f).coerceIn(0f, 1f))
            
        else -> rssiStrength / 5f
    }
    
    val warmth by animateFloatAsState(
        targetValue   = targetWarmth,
        animationSpec = tween(durationMillis = 700, easing = LinearEasing),
        label         = "warmth",
    )

    // Glow pulse speed scales from 1800ms (cold) to 400ms (hot)
    val pulseDuration = (1800 - (warmth * 1400).toInt()).coerceAtLeast(400)
    val inf = rememberInfiniteTransition(label = "heatPulse")
    val glowPulse by inf.animateFloat(
        initialValue  = 0.6f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(
            tween(pulseDuration, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "glowPulse",
    )

    val bgColor = lerp(COLD_BG, HOT_BG, warmth)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        // Central heat glow
        Canvas(modifier = Modifier.size(400.dp)) {
            val glowAlpha = (0.1f + warmth * 0.5f) * glowPulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF3B30).copy(alpha = glowAlpha),
                        Color(0xFFFF3B30).copy(alpha = glowAlpha * 0.3f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = size.width / 2f,
                ),
                radius = size.width / 2f,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 64.dp, bottom = 48.dp),
        ) {
            // ── Dynamic Header ────────────────────────────────────────────────
            val headerData = when {
                warmth >= 0.95f -> "YOU'RE THERE — STAY WITH THEM" to Color(0xFFFF3B30)
                warmth >= 0.75f -> "GETTING HOT — ALMOST THERE"     to Color(0xFFFF6B6B)
                warmth >= 0.5f  -> "WARMER — KEEP GOING"            to Color(0xFFFF9F1C)
                warmth >= 0.25f -> "FOLLOW THE SIGNAL"              to Color(0xFF4D96FF)
                else            -> "FIND THEM BY SIGNAL"             to Color(0xFF3A7BD5)
            }
            
            Text(
                text          = headerData.first,
                color         = headerData.second,
                fontWeight    = FontWeight.Black,
                fontSize      = 14.sp,
                letterSpacing = 2.sp,
                textAlign     = TextAlign.Center,
            )

            if (distanceMeters != null && distanceMeters > 2f) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .rotate(arrowRotation),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer decorative ring
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.15f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                            radius = size.width / 2f
                        )
                    }
                    
                    // The needle
                    Canvas(modifier = Modifier.size(80.dp)) {
                        val path = Path().apply {
                            moveTo(size.width / 2f, 0f)
                            lineTo(size.width, size.height)
                            lineTo(size.width / 2f, size.height * 0.75f)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(path, color = Color.White)
                    }
                }
            } else {
                // ── RSSI / Searching State ─────────────────────────────────────
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier         = Modifier.size(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val infinite = rememberInfiniteTransition(label = "homing")
                        val pulseScale by infinite.animateFloat(
                            initialValue = 0.85f, targetValue = 1.15f,
                            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
                            label = "pulse"
                        )
                        // Decorative rings
                        Box(Modifier.size(160.dp).background(Color.White.copy(alpha = 0.05f), CircleShape))
                        Box(Modifier.size(120.dp).background(Color.White.copy(alpha = 0.08f), CircleShape))
                        
                        Box(
                            Modifier
                                .size(80.dp)
                                .scale(pulseScale)
                                .background(Color.White.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(24.dp))
                    
                    // Signal Bars (matches the user's image)
                    val strength = rssiLabelToStrength(rssiLabel)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        for (i in 1..5) {
                            val active = i <= strength
                            val barHeight = (12 + i * 10).dp
                            Box(
                                modifier = Modifier
                                    .width(16.dp)
                                    .height(barHeight)
                                    .background(
                                        if (active) Color(0xFFFF9F1C) else Color.White.copy(alpha = 0.1f),
                                        RoundedCornerShape(4.dp)
                                    )
                            )
                        }
                    }
                }
            }

            // ── Distance / RSSI Readout ────────────────────────────────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (rssiStrength >= 5) {
                    Text("HERE", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black)
                } else if (distanceMeters != null && distanceMeters < 1000f) {
                    Text("${distanceMeters.toInt()}m", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black)
                } else {
                    Text(rssiLabel, color = Color(0xFFFF9F1C), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }

            // ── Instructions Card (matches the user's image) ──────────────────
            if (distanceMeters == null || distanceMeters > 5f) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "HOW TO FIND THEM",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        
                        val steps = listOf(
                            "Walk in any direction",
                            "Watch the bars — if they drop, turn around",
                            "Keep walking toward stronger signal",
                            "Very Close = you're there"
                        )
                        
                        steps.forEachIndexed { index, step ->
                            Row(verticalAlignment = Alignment.Top) {
                                Surface(
                                    modifier = Modifier.size(18.dp),
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.2f)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("${index + 1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(step, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                            if (index < steps.size - 1) Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            } else if (!assignedTask.isNullOrBlank()) {
                // Show task card only when arrived or GPS is active and close
                Surface(
                    shape    = RoundedCornerShape(16.dp),
                    color    = Color.Black.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "YOUR TASK",
                            color      = Color.White.copy(alpha = 0.6f),
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            assignedTask,
                            color      = Color.White,
                            fontSize   = 17.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // ── Arrived Button ────────────────────────────────────────────────
            Button(
                onClick  = onArrived,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape    = RoundedCornerShape(16.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF30D158),
                    contentColor   = Color.Black,
                ),
            ) {
                Text("I'VE ARRIVED", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }
    }
}
