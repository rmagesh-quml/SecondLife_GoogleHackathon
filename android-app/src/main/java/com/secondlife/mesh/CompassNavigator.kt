package com.secondlife.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CompassNavigator(private val context: Context) {

    private val _heading = MutableStateFlow(0f)
    val heading: StateFlow<Float> = _heading.asStateFlow()

    private val _distanceMeters = MutableStateFlow<Float?>(null)
    val distanceMeters: StateFlow<Float?> = _distanceMeters.asStateFlow()

    private val _bearing = MutableStateFlow(0f)
    val bearing: StateFlow<Float> = _bearing.asStateFlow()

    private val _rssiDistance = MutableStateFlow("Scanning...")
    val rssiDistance: StateFlow<String> = _rssiDistance.asStateFlow()

    private val _isGpsAvailable = MutableStateFlow(false)
    val isGpsAvailable: StateFlow<Boolean> = _isGpsAvailable.asStateFlow()

    // Arrow rotation = bearing minus heading, normalised 0-360
    private val _arrowRotation = MutableStateFlow(0f)
    val arrowRotation: StateFlow<Float> = _arrowRotation.asStateFlow()

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    private var targetLocation: Location? = null
    private var prevHeading = 0f

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val rotationMatrix = FloatArray(9)
            val orientationAngles = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            // Convert radians to degrees
            val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            val normalised = (azimuthDeg + 360) % 360
            // Low-pass filter to smooth jitter
            prevHeading = prevHeading * 0.85f + normalised * 0.15f
            _heading.value = prevHeading
            recalcArrow()
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val target = targetLocation ?: return
            _bearing.value = location.bearingTo(target)
            _distanceMeters.value = location.distanceTo(target)
            _isGpsAvailable.value = true
            recalcArrow()
        }
    }

    @SuppressLint("MissingPermission")
    fun startNavigation(targetLat: Double, targetLng: Double) {
        // Register rotation vector sensor for compass heading
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor != null) {
            sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }

        if (targetLat == 0.0 && targetLng == 0.0) {
            // GPS unavailable — RSSI-only mode
            _isGpsAvailable.value = false
            return
        }

        targetLocation = Location("target").apply {
            latitude  = targetLat
            longitude = targetLng
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateDistanceMeters(1f)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            _isGpsAvailable.value = false
        }
    }

    fun updateRSSI(rssi: Int) {
        _rssiDistance.value = when {
            rssi > -50 -> "Very close · <5m"
            rssi > -60 -> "Close · ~10m"
            rssi > -70 -> "Nearby · ~25m"
            rssi > -80 -> "In range · ~50m"
            rssi > -90 -> "Far · ~100m"
            else       -> "At limit · >100m"
        }
    }

    fun stopNavigation() {
        sensorManager.unregisterListener(sensorListener)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        targetLocation = null
        _heading.value = 0f
        _bearing.value = 0f
        _distanceMeters.value = null
        _isGpsAvailable.value = false
        _rssiDistance.value = "Scanning..."
        _arrowRotation.value = 0f
        prevHeading = 0f
    }

    private fun recalcArrow() {
        _arrowRotation.value = (_bearing.value - _heading.value + 360) % 360
    }
}
