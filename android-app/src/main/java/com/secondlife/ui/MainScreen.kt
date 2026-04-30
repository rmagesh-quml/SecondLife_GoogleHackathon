package com.secondlife.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secondlife.inference.SecondLifeViewModel
import com.secondlife.tts.TTSManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: SecondLifeViewModel, ttsManager: TTSManager) {
    val response by viewModel.response.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    LaunchedEffect(response) {
        response?.let {
            ttsManager.speak(it.response)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("SecondLife") }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                LargeFloatingActionButton(
                    onClick = { viewModel.query("How to treat a burn?") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Mic", modifier = Modifier.size(36.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Hold to speak clinical question", style = MaterialTheme.typography.bodySmall)
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                    if (isLoading) {
                        CircularProgressIndicator()
                    } else {
                        response?.let {
                            Text(
                                text = it.response,
                                fontSize = 20.sp,
                                lineHeight = 28.sp,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        } ?: Text(
                            "Ready to assist. Ask an emergency question.",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                response?.let {
                    AssistChip(
                        onClick = { },
                        label = { Text("Source: Protocol") },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.secondary
                        )
                    )
                    Text(
                        text = "${it.latencyMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            
            // Layout spacer for FAB area
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}
