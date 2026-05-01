package com.secondlife.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EmergencyFab(
    isListening: Boolean,
    isBroadcasting: Boolean,
    responderCount: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fab")

    // Idle pulse — subtle breathing effect
    val idleScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "idleScale",
    )

    // Listening rings — expand outward like sonar
    val ring1 by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 2.2f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "ring1",
    )
    val ring1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Restart),
        label = "ring1Alpha",
    )
    val ring2 by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 2.2f,
        animationSpec = infiniteRepeatable(tween(1000, delayMillis = 333, easing = LinearEasing), RepeatMode.Restart),
        label = "ring2",
    )
    val ring2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1000, delayMillis = 333), RepeatMode.Restart),
        label = "ring2Alpha",
    )

    val fabColor = when {
        isBroadcasting -> Color(0xFF2E7D32)
        isListening    -> Color(0xFFD32F2F)
        else           -> Color(0xFF8B0000)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Responder count badge — only visible while broadcasting
        if (isBroadcasting && responderCount > 0) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF1B5E20),
            ) {
                Text(
                    "$responderCount",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                )
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(72.dp),
        ) {
            // Sonar rings when listening
            if (isListening) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(ring1)
                        .background(Color(0xFFD32F2F).copy(alpha = ring1Alpha), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(ring2)
                        .background(Color(0xFFD32F2F).copy(alpha = ring2Alpha), CircleShape)
                )
            }

            // Main FAB button
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(if (!isListening && !isBroadcasting) idleScale else 1f)
                    .background(fabColor, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onLongPress = { onLongPress() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isBroadcasting -> Text("📡", fontSize = 28.sp)
                    isListening    -> Icon(
                        Icons.Default.Stop, contentDescription = "Stop",
                        tint = Color.White, modifier = Modifier.size(34.dp)
                    )
                    else           -> Icon(
                        Icons.Default.Mic, contentDescription = "Emergency",
                        tint = Color.White, modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }
}
