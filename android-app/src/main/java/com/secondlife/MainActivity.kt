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
    private lateinit var cameraManager: CameraManager

    private val meshPermissionKeys = setOf(
        android.Manifest.permission.BLUETOOTH,
        android.Manifest.permission.BLUETOOTH_ADMIN,
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_ADVERTISE,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val meshDenied = results.entries.any { (perm, granted) ->
            perm in meshPermissionKeys && !granted
        }
        if (meshDenied) {
            viewModel.postError("Enable Bluetooth for mesh emergency features")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ttsManager    = TTSManager(this)
        speechManager = SpeechManager(this)
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
        val meshPermissions = listOf(
            android.Manifest.permission.BLUETOOTH,
            android.Manifest.permission.BLUETOOTH_ADMIN,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val needed = (listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) + meshPermissions)
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
