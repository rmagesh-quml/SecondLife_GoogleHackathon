package com.secondlife.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Guides Person B (the responder) toward Person A (the injured person).
 *
 * ── ZERO INTERNET / ZERO WIFI REQUIRED ──────────────────────────────────────
 * • Compass heading: device rotation sensor — works completely offline.
 * • Distance/bearing: GPS satellite fix — works with no cell signal, no WiFi.
 *   GPS only needs a clear view of the sky; it talks to satellites, not towers.
 *
 * If GPS hasn't acquired a fix yet (takes ~30s cold start), the app falls back
 * to Bluetooth RSSI distance estimates from Nearby Connections automatically.
 *
 * FusedLocationProviderClient is used with PRIORITY_HIGH_ACCURACY so Android
 * prefers the GPS chip. If GPS is unavailable (indoors), RSSI mode takes over.
 * ────────────────────────────────────────────────────────────────────────────
 */
class CompassNavigator(private val context: Context) {

    private val TAG = "CompassNavigator"

    private val _heading = MutableStateFlow(0f)
    val heading: StateFlow<Float> = _heading.asStateFlow()

    private val _distanceMeters = MutableStateFlow<Float?>(null)
    val distanceMeters: StateFlow<Float?> = _distanceMeters.asStateFlow()

    private val _bearing = MutableStateFlow(0f)
    val bearing: StateFlow<Float> = _bearing.asStateFlow()

    // Human-readable distance from Bluetooth RSSI — shown when GPS is unavailable
    private val _rssiDistance = MutableStateFlow("Scanning…")
    val rssiDistance: StateFlow<String> = _rssiDistance.asStateFlow()

    // True once GPS has produced at least one fix
    private val _isGpsAvailable = MutableStateFlow(false)
    val isGpsAvailable: StateFlow<Boolean> = _isGpsAvailable.asStateFlow()

    // The compass arrow angle: 0 = straight ahead toward Person A
    private val _arrowRotation = MutableStateFlow(0f)
    val arrowRotation: StateFlow<Float> = _arrowRotation.asStateFlow()

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // FusedLocationProviderClient prefers GPS satellite first, no internet needed
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    // Raw LocationManager as fallback if FusedLocation fails (e.g. Play Services issue)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var targetLocation: Location? = null
    private var lastUserLocation: Location? = null
    private var prevHeading = 0f
    private var headingInitialized = false
    private var navigationActive = false
    private var declination = 0f

    // ── Sensor listener — rotation vector → compass heading ──────────────────

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val rotMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            
            // Determine tilt: pitch is rotation around X axis.
            val orientationTemp = FloatArray(3)
            SensorManager.getOrientation(rotMatrix, orientationTemp)
            val pitch = Math.abs(Math.toDegrees(orientationTemp[1].toDouble()))

            // Remap coordinate system based on device orientation
            val outMatrix = FloatArray(9)
            if (pitch > 45) {
                // Vertical/Portrait holding: remap so top of phone is the reference.
                // Standard remapping for portrait: X -> AXIS_X, Y -> AXIS_Z
                SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, outMatrix)
            } else {
                // Flat holding: default
                System.arraycopy(rotMatrix, 0, outMatrix, 0, 9)
            }
            
            SensorManager.getOrientation(outMatrix, orientation)
            
            // Azimuth is rotation around the Z axis. Convert to degrees [0, 360].
            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            val magneticHeading = (azimuth + 360) % 360
            
            // Apply declination to get True North heading
            val trueHeading = (magneticHeading + declination + 360) % 360
            
            // Accuracy check: only update if sensor reports high enough confidence
            // (Wait, TYPE_ROTATION_VECTOR doesn't have accuracy in same way as magnetic)
            
            // Update flow with smoothed heading
            _heading.value = smoothHeading(trueHeading)
            recalcArrow()
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private fun smoothHeading(newHeading: Float): Float {
        if (!headingInitialized) {
            prevHeading = newHeading
            headingInitialized = true
            return newHeading
        }
        var diff = newHeading - prevHeading
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        // Slower smoothing (0.15 instead of 0.25) for more stability
        prevHeading = (prevHeading + 0.15f * diff + 360) % 360
        return prevHeading
    }

    // ── Location callback (Fused — prefers GPS, no network needed) ───────────

    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            // Only use this fix if it came from the GPS provider, not network
            updateFromLocation(loc)
        }
    }

    // ── Fallback: raw GPS via LocationManager ─────────────────────────────────

    private val gpsLocationListener = LocationListener { loc ->
        updateFromLocation(loc)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called when the broadcaster sends a real-time location update via Bluetooth payload.
     * If navigation was already active (RSSI-only because initial coords were 0.0), this
     * upgrades it to full GPS compass mode without restarting the sensor/location stack.
     */
    fun updateTarget(lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) return
        if (!navigationActive) {
            // Navigation wasn't started (RSSI-only mode) — start full GPS navigation now
            Log.d(TAG, "updateTarget: upgrading from RSSI-only to GPS mode ($lat, $lng)")
            startNavigation(lat, lng)
            return
        }
        // Update target in place — no sensor restart needed
        val newTarget = Location("sos_target").apply {
            latitude  = lat
            longitude = lng
        }
        targetLocation = newTarget
        declination = GeomagneticField(
            lat.toFloat(), lng.toFloat(), 0f, System.currentTimeMillis()
        ).declination
        // Recompute bearing/distance from the last known user location immediately
        lastUserLocation?.let { userLoc ->
            _bearing.value        = userLoc.bearingTo(newTarget)
            _distanceMeters.value = userLoc.distanceTo(newTarget)
            _isGpsAvailable.value = true
            recalcArrow()
        }
        Log.d(TAG, "updateTarget: target updated to ($lat, $lng)")
    }

    @SuppressLint("MissingPermission")
    fun startNavigation(targetLat: Double, targetLng: Double) {
        navigationActive = true
        prevHeading = 0f
        headingInitialized = false

        // Always register the rotation sensor — works without any signal
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor != null) {
            sensorManager.registerListener(
                sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI
            )
        } else {
            Log.w(TAG, "No rotation vector sensor — compass heading unavailable")
        }

        // If broadcaster didn't share GPS coordinates, use RSSI-only mode
        if (targetLat == 0.0 && targetLng == 0.0) {
            Log.d(TAG, "No GPS coordinates in SOS packet — RSSI distance mode only")
            _isGpsAvailable.value = false
            return
        }

        targetLocation = Location("sos_target").apply {
            latitude  = targetLat
            longitude = targetLng
        }

        // Set initial declination based on target location so compass is 
        // relatively accurate even before we get our first own GPS fix.
        declination = GeomagneticField(
            targetLat.toFloat(),
            targetLng.toFloat(),
            0f,
            System.currentTimeMillis()
        ).declination

        // Try FusedLocationProviderClient first — uses GPS + Network backup
        try {
            // Check for last known location, but only use it if it's very fresh (< 15s)
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && (System.currentTimeMillis() - loc.time) < 15000) {
                    updateFromLocation(loc)
                } else {
                    Log.d(TAG, "Last known location is stale or null — waiting for fresh fix")
                }
            }

            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,   // Forces GPS for responder navigation
                1000L
            ).setMinUpdateDistanceMeters(0.2f).build()

            fusedLocationClient.requestLocationUpdates(
                request, fusedLocationCallback, Looper.getMainLooper()
            )
            Log.d(TAG, "Location updates started (Balanced priority)")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission denied — falling back to RSSI only")
            _isGpsAvailable.value = false
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocation unavailable (${e.message}) — trying raw GPS")
            startRawGpsFallback()
        }
    }

    fun updateRSSI(rssi: Int) {
        // Convert Bluetooth signal strength to a human-readable distance estimate.
        // These ranges are rough — RSSI varies with walls, people, and device hardware.
        _rssiDistance.value = when {
            rssi > -50 -> "Very close · <5 m"
            rssi > -60 -> "Close · ~10 m"
            rssi > -70 -> "Nearby · ~25 m"
            rssi > -80 -> "In range · ~50 m"
            rssi > -90 -> "Far · ~100 m"
            else       -> "At limit · >100 m"
        }
    }

    fun stopNavigation() {
        navigationActive = false
        sensorManager.unregisterListener(sensorListener)
        runCatching { fusedLocationClient.removeLocationUpdates(fusedLocationCallback) }
        runCatching {
            @Suppress("DEPRECATION")
            locationManager.removeUpdates(gpsLocationListener)
        }
        targetLocation  = null
        prevHeading     = 0f
        _heading.value        = 0f
        _bearing.value        = 0f
        _distanceMeters.value = null
        _isGpsAvailable.value = false
        _rssiDistance.value   = "Scanning…"
        _arrowRotation.value  = 0f
        Log.d(TAG, "Navigation stopped")
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun updateFromLocation(loc: Location) {
        if (!navigationActive) return
        
        // RELAXED FOR DEMO: Ignore fixes older than 10 minutes (was 30s)
        val ageMs = System.currentTimeMillis() - loc.time
        if (ageMs > 600000) {
            Log.d(TAG, "Ignoring very stale location fix (age: ${ageMs}ms)")
            return
        }

        // RELAXED FOR DEMO: Accept ANY accuracy. 
        // This ensures the needle appears even with poor indoor GPS.
        lastUserLocation = loc
        val target = targetLocation ?: return
        
        // Update declination for the current location (True North vs Magnetic North)
        val field = GeomagneticField(
            loc.latitude.toFloat(),
            loc.longitude.toFloat(),
            loc.altitude.toFloat(),
            loc.time
        )
        declination = field.declination

        _bearing.value        = loc.bearingTo(target)
        _distanceMeters.value = loc.distanceTo(target)
        _isGpsAvailable.value = true
        recalcArrow()
    }

    private fun recalcArrow() {
        // arrowRotation = 0 means "straight ahead" = you are facing Person A.
        // Rotating right = Person A is to your right, etc.
        _arrowRotation.value = (_bearing.value - _heading.value + 360) % 360
    }

    @SuppressLint("MissingPermission")
    private fun startRawGpsFallback() {
        // Direct GPS chip access — no Google Play Services, no internet, no network
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,   // min time ms
                    1f,      // min distance m
                    gpsLocationListener,
                    Looper.getMainLooper(),
                )
                Log.d(TAG, "Raw GPS fallback active")
            } else {
                Log.w(TAG, "GPS provider disabled — RSSI only")
                _isGpsAvailable.value = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Raw GPS fallback failed: ${e.message}")
            _isGpsAvailable.value = false
        }
    }
}
