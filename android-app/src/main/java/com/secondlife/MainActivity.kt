package com.secondlife

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.secondlife.audio.AudioCaptureManager
import com.secondlife.audio.SpeechManager
import com.secondlife.camera.CameraManager
import com.secondlife.inference.SecondLifeViewModel
import com.secondlife.tts.TTSManager
import com.secondlife.ui.MainScreen
import com.secondlife.ui.theme.SecondLifeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: SecondLifeViewModel by viewModels()

    private lateinit var ttsManager:   TTSManager
    private lateinit var speechManager: SpeechManager
    private lateinit var audioCapture: AudioCaptureManager
    private lateinit var cameraManager: CameraManager

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled gracefully in the UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ttsManager   = TTSManager(this)
        speechManager = SpeechManager(this)
        audioCapture = AudioCaptureManager(this)
        cameraManager = CameraManager(this, this)   // 'this' as LifecycleOwner

        // Wire speech → query pipeline
        speechManager.onResult = { transcript ->
            viewModel.query(text = transcript)
        }
        speechManager.onError = { msg ->
            viewModel.postError(msg)
        }

        // Initialise camera in background
        lifecycleScope.launch {
            runCatching { cameraManager.initialize() }
        }

        // Request permissions upfront
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray())

        enableEdgeToEdge()
        setContent {
            SecondLifeTheme {
                MainScreen(
                    viewModel     = viewModel,
                    ttsManager    = ttsManager,
                    speechManager = speechManager,
                    cameraManager = cameraManager,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::ttsManager.isInitialized)    ttsManager.release()
        if (::speechManager.isInitialized) speechManager.destroy()
        if (::cameraManager.isInitialized) cameraManager.release()
    }
}
