package com.secondlife.mesh

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.secondlife.notification.EmergencyNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MeshService : Service() {
    private val TAG = "MeshService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    lateinit var meshManager: MeshManager
        private set

    private val _nearbyEmergency = MutableStateFlow<MeshManager.EmergencyBroadcast?>(null)
    val nearbyEmergency: StateFlow<MeshManager.EmergencyBroadcast?> = _nearbyEmergency.asStateFlow()

    private val _pendingEndpointId = MutableStateFlow<String?>(null)
    val pendingEndpointId: StateFlow<String?> = _pendingEndpointId.asStateFlow()

    private val _responderCount = MutableStateFlow(0)
    val responderCount: StateFlow<Int> = _responderCount.asStateFlow()

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private val _assignedTask = MutableStateFlow<String?>(null)
    val assignedTask: StateFlow<String?> = _assignedTask.asStateFlow()

    private val _rssi = MutableStateFlow(-100)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()

    inner class MeshBinder : Binder() {
        fun getService(): MeshService = this@MeshService
    }

    private val binder = MeshBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MeshService created")
        
        EmergencyNotificationManager.createChannels(this)
        startForeground(1001, createServiceNotification())

        meshManager = MeshManager(
            context             = this,
            onEmergencyReceived = { broadcast, endpointId -> onEmergencyReceived(broadcast, endpointId) },
            onResponderJoined   = { endpointId, count -> onResponderJoined(endpointId, count) },
            onTaskReceived      = { task -> _assignedTask.value = task },
            onRSSIUpdate        = { rssi -> _rssi.value = rssi },
        )

        meshManager.startScanning()

        // ── Watchdog ─────────────────────────────────────────────────────────
        // Nearby Connections can sometimes become "stale" at range limits.
        // Restarting the scan every 45s keeps the radio fresh.
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(45_000L)
                if (!_isBroadcasting.value && meshManager.isScanActive) {
                    Log.d(TAG, "Watchdog: Refreshing background scan for better range")
                    meshManager.stopScanning()
                    kotlinx.coroutines.delay(500L)
                    meshManager.startScanning()
                }
            }
        }
    }

    private fun createServiceNotification() = 
        androidx.core.app.NotificationCompat.Builder(this, "secondlife_active")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SecondLife Active")
            .setContentText("Scanning for nearby emergencies")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun onEmergencyReceived(broadcast: MeshManager.EmergencyBroadcast, endpointId: String) {
        _pendingEndpointId.value = endpointId
        _nearbyEmergency.value = broadcast
        
        // Buzz the phone so user feels it even if it's in their pocket
        val vibrator = getSystemService(android.os.Vibrator::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(800, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(800)
        }

        // Background alert!
        EmergencyNotificationManager.showAlert(
            this,
            broadcast.type,
            broadcast.summary,
            endpointId
        )
    }

    private fun onResponderJoined(endpointId: String, count: Int) {
        _responderCount.value = count
        EmergencyNotificationManager.showBroadcasting(this, count)
    }

    fun setIsBroadcasting(active: Boolean) {
        _isBroadcasting.value = active
        if (active) {
            EmergencyNotificationManager.showBroadcasting(this, _responderCount.value)
        } else {
            EmergencyNotificationManager.showActive(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        meshManager.release()
        Log.d(TAG, "MeshService destroyed")
    }
}
