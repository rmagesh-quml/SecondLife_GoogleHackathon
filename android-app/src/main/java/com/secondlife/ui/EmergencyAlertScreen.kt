package com.secondlife.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secondlife.mesh.MeshManager

/**
 * Full-screen overlay shown to a bystander (Person B) when their device
 * detects a nearby SecondLife SOS broadcast. They can accept to help or dismiss.
 */
@Composable
fun EmergencyAlertScreen(
    broadcast: MeshManager.EmergencyBroadcast,
    rssiLabel: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Pulsing red ring animation to convey urgency
    val pulse = rememberInfiniteTransition(label = "alertPulse")
    val ringScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue  = 1.25f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "ringScale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD0A0A0A)),   // dark translucent overlay
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(Color(0xFF1C1C1E), RoundedCornerShape(24.dp))
                .padding(28.dp),
        ) {
            // Pulsing SOS icon
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .scale(ringScale)
                        .background(Color(0x33FF3B30), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .background(Color(0xFFFF3B30), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🆘", fontSize = 32.sp)
                }
            }

            Text(
                "EMERGENCY NEARBY",
                color      = Color(0xFFFF3B30),
                fontWeight = FontWeight.ExtraBold,
                fontSize   = 20.sp,
                letterSpacing = 2.sp,
            )

            // Emergency type badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF2C1B1B),
            ) {
                Text(
                    broadcast.type.replace("_", " ").uppercase(),
                    modifier  = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    color     = Color(0xFFFF6B6B),
                    fontWeight = FontWeight.Bold,
                    fontSize  = 13.sp,
                    letterSpacing = 1.sp,
                )
            }

            // What happened
            Text(
                broadcast.summary,
                color     = Color.White,
                fontSize  = 17.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
            )

            // Distance estimate from RSSI
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(8.dp).background(Color(0xFF30D158), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    rssiLabel,
                    color    = Color(0xFF30D158),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Text(
                "Someone needs your help right now.\nYou can guide them to safety.",
                color     = Color(0xFFAAAAAA),
                fontSize  = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )

            Spacer(Modifier.height(4.dp))

            // Accept button
            Button(
                onClick = onAccept,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape  = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF30D158),
                    contentColor   = Color.Black,
                ),
            ) {
                Text(
                    "🧭  I'll Help — Navigate to Them",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp,
                )
            }

            // Dismiss button
            TextButton(onClick = onDismiss) {
                Text(
                    "I can't help right now",
                    color    = Color(0xFF666666),
                    fontSize = 13.sp,
                )
            }
        }
    }
}
