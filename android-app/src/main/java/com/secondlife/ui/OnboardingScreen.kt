package com.secondlife.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val ONBOARDING_PAGES = listOf(
    Triple("📡", "Works Without Internet", "No signal? No problem. SecondLife works on Bluetooth alone."),
    Triple("🎤", "Speak or Point", "Describe the emergency out loud or point your camera at the scene."),
    Triple("🔴", "Alert Nearby Phones", "SecondLife automatically alerts other phones nearby over Bluetooth."),
)

private val ONBOARDING_ROLES = listOf(
    "layperson" to "Layperson",
    "paramedic" to "Paramedic",
    "military_medic" to "Military Medic",
)

@Composable
fun OnboardingScreen(onComplete: (role: String) -> Unit) {
    // 3 intro pages + 1 role selector page
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGES.size + 1 })
    val scope = rememberCoroutineScope()
    var selectedRole by remember { mutableStateOf("layperson") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            if (page < ONBOARDING_PAGES.size) {
                val (icon, title, subtitle) = ONBOARDING_PAGES[page]
                OnboardingPage(icon, title, subtitle)
            } else {
                RoleSelectorPage(
                    selectedRole = selectedRole,
                    onRoleSelected = { selectedRole = it },
                )
            }
        }

        // Page dots
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(ONBOARDING_PAGES.size + 1) { i ->
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(if (pagerState.currentPage == i) 10.dp else 7.dp)
                        .background(
                            if (pagerState.currentPage == i)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                )
            }
        }

        // Next / Get Started button
        Button(
            onClick = {
                if (pagerState.currentPage < ONBOARDING_PAGES.size) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                } else {
                    onComplete(selectedRole)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                if (pagerState.currentPage < ONBOARDING_PAGES.size) "Next →" else "Get Started",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun OnboardingPage(icon: String, title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(icon, fontSize = 80.sp)
        Spacer(Modifier.height(32.dp))
        Text(
            title,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            subtitle,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RoleSelectorPage(
    selectedRole: String,
    onRoleSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("👤", fontSize = 60.sp)
        Spacer(Modifier.height(24.dp))
        Text(
            "Who are you?",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "SecondLife adapts its guidance to your level.",
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        ONBOARDING_ROLES.forEach { (key, label) ->
            val selected = selectedRole == key
            OutlinedButton(
                onClick = { onRoleSelected(key) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = if (selected) ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) else ButtonDefaults.outlinedButtonColors(),
                border = if (selected)
                    androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else
                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    label,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 15.sp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
