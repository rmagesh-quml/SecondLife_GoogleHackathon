package com.secondlife

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import com.secondlife.audio.AudioCaptureManager
import com.secondlife.inference.SecondLifeViewModel
import com.secondlife.tts.TTSManager
import com.secondlife.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val viewModel: SecondLifeViewModel by viewModels()
    private lateinit var ttsManager: TTSManager
    private lateinit var audioCapture: AudioCaptureManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result handled silently; mic FAB checks state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ttsManager   = TTSManager(this)
        audioCapture = AudioCaptureManager(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainScreen(viewModel, ttsManager, audioCapture)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::ttsManager.isInitialized) ttsManager.release()
    }
}
