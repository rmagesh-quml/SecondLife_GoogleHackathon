package com.secondlife

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.secondlife.audio.SpeechManager
import com.secondlife.camera.CameraManager
import com.secondlife.inference.SecondLifeViewModel
import com.secondlife.notification.EmergencyNotificationManager
import com.secondlife.tts.TTSManager
import com.secondlife.ui.EmergencyAlertScreen
import com.secondlife.ui.EmergencyFab
import com.secondlife.ui.MainScreen
import com.secondlife.ui.OnboardingScreen
import com.secondlife.ui.ResponderCompassScreen
import com.secondlife.ui.theme.SecondLifeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: SecondLifeViewModel by viewModels()

    private lateinit var ttsManager:   TTSManager
    private lateinit var speechManager: SpeechManager
    private lateinit var cameraManager: CameraManager

    // ── Onboarding state (Activity-level so it survives recomposition) ───────
    private val prefs by lazy { getSharedPreferences("secondlife", Context.MODE_PRIVATE) }
    private var showOnboarding by mutableStateOf(false)  // initialized in onCreate

    // ── Shake-to-activate ────────────────────────────────────────────────────
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private var lastShakeTime = 0L
    private val SHAKE_THRESHOLD = 25f
    private val SHAKE_DEBOUNCE_MS = 3000L

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            val now = System.currentTimeMillis()
            if (magnitude > SHAKE_THRESHOLD && now - lastShakeTime > SHAKE_DEBOUNCE_MS) {
                lastShakeTime = now
                // Haptic feedback
                @Suppress("DEPRECATION")
                val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                    getSystemService(VibratorManager::class.java).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Vibrator::class.java)
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
                Toast.makeText(this@MainActivity, "Emergency mode activated", Toast.LENGTH_SHORT).show()
                speechManager.toggle()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

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

        // Onboarding: read persisted flag
        showOnboarding = !prefs.getBoolean("onboarded", false)

        // ── Notification setup ───────────────────────────────────────────────
        EmergencyNotificationManager.createChannel(this)
        // Show the persistent "active" notification after we have permission
        EmergencyNotificationManager.showActive(this)

        // Observe broadcasting state → update notification
        lifecycleScope.launch {
            viewModel.isBroadcasting.collect { broadcasting ->
                if (broadcasting) {
                    EmergencyNotificationManager.showBroadcasting(
                        this@MainActivity,
                        viewModel.responderCount.value,
                    )
                } else {
                    EmergencyNotificationManager.showActive(this@MainActivity)
                }
            }
        }

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
        val notifPermission = if (Build.VERSION.SDK_INT >= 33) {
            listOf(android.Manifest.permission.POST_NOTIFICATIONS)
        } else emptyList()

        val needed = (listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        ) + meshPermissions + notifPermission)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray())

        enableEdgeToEdge()

        setContent {
            SecondLifeTheme {
                if (showOnboarding) {
                    OnboardingScreen { role ->
                        prefs.edit()
                            .putBoolean("onboarded", true)
                            .putString("role", role)
                            .apply()
                        viewModel.setRole(role)
                        showOnboarding = false
                    }
                } else {
                    // ── Collect state needed for FAB and overlays ────────────
                    val isListening    by speechManager.isListening.collectAsStateWithLifecycle()
                    val isBroadcasting by viewModel.isBroadcasting.collectAsStateWithLifecycle()
                    val responderCount by viewModel.responderCount.collectAsStateWithLifecycle()
                    val nearbyEmergency by viewModel.nearbyEmergency.collectAsStateWithLifecycle()
                    val isResponder    by viewModel.isResponder.collectAsStateWithLifecycle()
                    val assignedTask   by viewModel.assignedTask.collectAsStateWithLifecycle()

                    // Compass state from CompassNavigator
                    val arrowRotation  by viewModel.navigator.arrowRotation.collectAsStateWithLifecycle()
                    val distanceMeters by viewModel.navigator.distanceMeters.collectAsStateWithLifecycle()
                    val rssiLabel      by viewModel.navigator.rssiDistance.collectAsStateWithLifecycle()
                    val isGpsAvailable by viewModel.navigator.isGpsAvailable.collectAsStateWithLifecycle()

                    val scope = androidx.compose.runtime.rememberCoroutineScope()

                    Box(modifier = Modifier.fillMaxSize()) {
                        // ── Main app UI ──────────────────────────────────────
                        MainScreen(
                            viewModel     = viewModel,
                            ttsManager    = ttsManager,
                            speechManager = speechManager,
                            cameraManager = cameraManager,
                        )

                        // ── Person B: Incoming SOS alert overlay ─────────────
                        nearbyEmergency?.let { broadcast ->
                            EmergencyAlertScreen(
                                broadcast  = broadcast,
                                rssiLabel  = rssiLabel,
                                onAccept   = { viewModel.acceptEmergency() },
                                onDismiss  = { viewModel.dismissEmergency() },
                            )
                        }

                        // ── Person B: Compass navigation screen ──────────────
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

                        // ── Global Emergency FAB — always on top ─────────────
                        EmergencyFab(
                            isListening    = isListening,
                            isBroadcasting = isBroadcasting,
                            responderCount = responderCount,
                            onTap          = { speechManager.toggle() },
                            onLongPress    = {
                                scope.launch {
                                    runCatching { cameraManager.captureFrame() }
                                        .onSuccess { viewModel.setCapturedImage(it) }
                                    speechManager.toggle()
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                                .navigationBarsPadding(),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(shakeListener)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            android.util.Log.w("SECONDLIFE", "Memory pressure level=$level — trimming non-essential resources")
            // Release camera when under memory pressure (it re-initializes on next use)
            if (::cameraManager.isInitialized) cameraManager.release()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::ttsManager.isInitialized)    ttsManager.release()
        if (::speechManager.isInitialized) speechManager.destroy()
        if (::cameraManager.isInitialized) cameraManager.release()
    }
}
