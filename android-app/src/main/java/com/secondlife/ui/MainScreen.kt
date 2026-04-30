package com.secondlife.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secondlife.inference.SecondLifeResponse
import com.secondlife.inference.SecondLifeViewModel

@Composable
fun MainScreen(viewModel: SecondLifeViewModel) {
    val response by viewModel.response.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "SecondLife",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        ResponseCard(response)

        Spacer(modifier = Modifier.weight(1f))

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
        }

        MicButton(
            isListening = isListening,
            onPress = { viewModel.setListening(true) },
            onRelease = { viewModel.setListening(false) },
        )
    }
}

@Composable
private fun ResponseCard(response: SecondLifeResponse?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 200.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (response == null) {
                Text("Hold the mic button and ask a question.", color = MaterialTheme.colorScheme.outline)
            } else {
                Text(response.response, style = MaterialTheme.typography.bodyLarge)
                if (response.citation.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Source: ${response.citation}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "~${response.latencyMs / 1000.0} s  ·  ${response.role}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun MicButton(isListening: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    Button(
        onClick = {},
        modifier = Modifier.size(80.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isListening)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.primary
        )
    ) {
        Text(if (isListening) "●" else "🎙")
    }
}
