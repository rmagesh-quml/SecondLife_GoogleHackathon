package com.secondlife

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.secondlife.audio.SpeechManager
import com.secondlife.camera.CameraManager
import com.secondlife.inference.SecondLifeViewModel
import com.secondlife.tts.TTSManager
import com.secondlife.ui.EmergencyAlertScreen
import com.secondlife.ui.MainScreen
import com.secondlife.ui.ResponderCompassScreen
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
        cameraManager = CameraManager(this, this)

        // Wire speech → query pipeline
        speechManager.onResult = { transcript -> viewModel.query(text = transcript) }
        speechManager.onError  = { msg -> viewModel.postError(msg) }

        // Initialise camera in background
        lifecycleScope.launch { runCatching { cameraManager.initialize() } }

        // Request all permissions upfront
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
                // ── Collect mesh/compass state ───────────────────────────────
                val nearbyEmergency by viewModel.nearbyEmergency.collectAsStateWithLifecycle()
                val isResponder     by viewModel.isResponder.collectAsStateWithLifecycle()
                val assignedTask    by viewModel.assignedTask.collectAsStateWithLifecycle()
                val pendingEndpoint by viewModel.pendingEndpointId.collectAsStateWithLifecycle()

                // Compass state from CompassNavigator
                val arrowRotation   by viewModel.navigator.arrowRotation.collectAsStateWithLifecycle()
                val distanceMeters  by viewModel.navigator.distanceMeters.collectAsStateWithLifecycle()
                val rssiLabel       by viewModel.navigator.rssiDistance.collectAsStateWithLifecycle()
                val isGpsAvailable  by viewModel.navigator.isGpsAvailable.collectAsStateWithLifecycle()

                // ── Main app UI — always rendered underneath ─────────────────
                MainScreen(
                    viewModel     = viewModel,
                    ttsManager    = ttsManager,
                    speechManager = speechManager,
                    cameraManager = cameraManager,
                )

                // ── Person B: Incoming SOS alert overlay ─────────────────────
                // Shown on top of MainScreen when a nearby emergency is detected.
                // Person A's SOS beacon was picked up by Nearby Connections.
                nearbyEmergency?.let { broadcast ->
                    EmergencyAlertScreen(
                        broadcast  = broadcast,
                        rssiLabel  = rssiLabel,
                        onAccept   = { viewModel.acceptEmergency() },
                        onDismiss  = { viewModel.dismissEmergency() },
                    )
                }

                // ── Person B: Compass navigation screen ──────────────────────
                // Replaces the alert screen once Person B accepts the SOS.
                // The arrow points toward Person A's last known GPS position.
                if (isResponder) {
                    ResponderCompassScreen(
                        arrowRotation  = arrowRotation,
                        distanceMeters = distanceMeters,
                        rssiLabel      = rssiLabel,
                        isGpsAvailable = isGpsAvailable,
                        assignedTask   = assignedTask,
                        onArrived      = { viewModel.arrivedAtScene() },
                    )
                }
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
