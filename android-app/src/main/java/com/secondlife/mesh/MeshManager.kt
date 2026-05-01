package com.secondlife.mesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Offline peer-to-peer emergency mesh using Google Nearby Connections.
 *
 * ── ZERO INTERNET REQUIRED ──────────────────────────────────────────────────
 * Nearby Connections is purely device-to-device radio:
 *   • Discovery  → Bluetooth LE advertising/scanning (no pairing needed)
 *   • Data transfer → Bluetooth Classic or WiFi Direct (phone-to-phone only,
 *     NOT your home WiFi router — the devices create their own radio link)
 *
 * The WiFi permissions in the manifest (ACCESS_WIFI_STATE, NEARBY_WIFI_DEVICES)
 * are only for WiFi Direct — they do NOT require a WiFi network or internet.
 * This works in airplane mode as long as Bluetooth is on.
 *
 * Typical range: ~30m Bluetooth, ~100m WiFi Direct (when available)
 * ────────────────────────────────────────────────────────────────────────────
 */
class MeshManager(
    private val context: Context,
    private val onEmergencyReceived: (EmergencyBroadcast, String) -> Unit,
    private val onResponderJoined: (String, Int) -> Unit,
    private val onTaskReceived: (String) -> Unit,
    private val onRSSIUpdate: (Int) -> Unit,
) {
    private val TAG = "MeshManager"
    private val SERVICE_ID = "com.secondlife.emergency"

    // P2P_CLUSTER: M-to-N discovery. Most resilient for mobile devices finding
    // each other in unpredictable environments without a fixed hub.
    private val STRATEGY = Strategy.P2P_CLUSTER

    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }

    private val connectedEndpoints = mutableListOf<String>()
    private val discoveredEndpoints = mutableMapOf<String, EmergencyBroadcast>()

    data class EmergencyBroadcast(
        val severity: Int,
        val type: String,
        val summary: String,
        val sessionId: String,
        val respondersNeeded: Int,
        val broadcasterLat: Double,   // 0.0 = GPS unavailable
        val broadcasterLng: Double,
        val broadcasterAccuracy: Float,
    )

    data class SessionContext(
        val broadcast: EmergencyBroadcast,
        val fullGuidance: String,
        val assignedTask: String,
    )

    // ── Connection lifecycle ──────────────────────────────────────────────────

    private val connectionLifecycleCallback: ConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                Log.d(TAG, "Connection request from $endpointId — auto-accepting (no internet needed)")
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                    .addOnFailureListener { e -> Log.w(TAG, "acceptConnection failed: ${e.message}") }
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                when (result.status.statusCode) {
                    ConnectionsStatusCodes.STATUS_OK -> {
                        Log.d(TAG, "✅ P2P link established with $endpointId (Bluetooth/WiFi Direct)")
                        if (!connectedEndpoints.contains(endpointId)) {
                            connectedEndpoints.add(endpointId)
                            onResponderJoined(endpointId, connectedEndpoints.size)
                        }
                    }
                    ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                        Log.w(TAG, "Connection rejected by $endpointId")
                    else -> {
                        Log.w(TAG, "Connection failed (${result.status.statusCode}) — retrying in 2s")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            connectionsClient.requestConnection(
                                SERVICE_ID, endpointId, connectionLifecycleCallback
                            ).addOnFailureListener { Log.w(TAG, "Retry failed for $endpointId") }
                        }, 2000)
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                Log.d(TAG, "Disconnected from $endpointId")
                connectedEndpoints.remove(endpointId)
                onResponderJoined(endpointId, connectedEndpoints.size)
            }
        }

    // ── Endpoint discovery ────────────────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            // Deduplicate: the watchdog restarts discovery every 20s, which re-fires
            // onEndpointFound for every already-known endpoint. Without this guard,
            // users get a new alert notification every 20s for the same broadcaster.
            if (discoveredEndpoints.containsKey(endpointId)) {
                Log.d(TAG, "onEndpointFound: $endpointId already known — skipping duplicate alert")
                return
            }
            Log.d(TAG, "🚨 SecondLife SOS detected from $endpointId via Bluetooth")
            runCatching {
                val broadcast = decodeBroadcast(info.endpointName)
                discoveredEndpoints[endpointId] = broadcast
                onEmergencyReceived(broadcast, endpointId)
                // Nearby doesn't expose raw RSSI — use a reasonable mid-range default.
                onRSSIUpdate(-70)
            }.onFailure { e ->
                Log.w(TAG, "Could not decode SOS packet from $endpointId: ${e.message}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "SOS signal lost from $endpointId (moved out of range)")
            discoveredEndpoints.remove(endpointId)
        }
    }

    // ── Payload handling ──────────────────────────────────────────────────────

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val text = String(bytes, StandardCharsets.UTF_8)
            when {
                // Task string sent by broadcaster to this responder
                text.startsWith("TASK:") -> onTaskReceived(text.removePrefix("TASK:"))
                // Full session context JSON
                text.startsWith("CTX:") -> {
                    runCatching {
                        val task = JSONObject(text.removePrefix("CTX:"))
                            .optString("assignedTask", "Assist the primary responder")
                        onTaskReceived(task)
                    }.onFailure { Log.w(TAG, "CTX parse failed: ${it.message}") }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // ── Broadcaster side (Person A — the injured person) ─────────────────────

    fun startBroadcasting(broadcast: EmergencyBroadcast) {
        Log.d(TAG, "📡 SOS broadcast starting [${broadcast.type}]...")

        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .setLowPower(false)
            .build()

        connectionsClient.startAdvertising(
            encodeBroadcast(broadcast),
            SERVICE_ID,
            connectionLifecycleCallback,
            options,
        ).addOnSuccessListener {
            Log.d(TAG, "✅ SOS broadcasting active at HIGH power")
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Advertising failed: ${e.message}")
        }
    }

    fun stopBroadcasting() {
        connectionsClient.stopAdvertising()
        connectedEndpoints.toList().forEach { connectionsClient.disconnectFromEndpoint(it) }
        connectedEndpoints.clear()
        Log.d(TAG, "SOS broadcast stopped")
    }

    fun sendTaskAssignment(endpointId: String, task: String) {
        // Tiny payload — sends instantly over Bluetooth
        val payload = Payload.fromBytes("TASK:$task".toByteArray(StandardCharsets.UTF_8))
        connectionsClient.sendPayload(endpointId, payload)
            .addOnFailureListener { e -> Log.w(TAG, "sendTask failed: ${e.message}") }
    }

    fun sendSessionContext(endpointId: String, ctx: SessionContext) {
        val json = JSONObject().apply {
            put("type", ctx.broadcast.type)
            put("summary", ctx.broadcast.summary)
            put("fullGuidance", ctx.fullGuidance)
            put("assignedTask", ctx.assignedTask)
        }
        val payload = Payload.fromBytes("CTX:$json".toByteArray(StandardCharsets.UTF_8))
        connectionsClient.sendPayload(endpointId, payload)
            .addOnFailureListener { e -> Log.w(TAG, "sendContext failed: ${e.message}") }
    }

    fun getConnectedEndpoints(): List<String> = connectedEndpoints.toList()

    // ── Scanner side (Person B — the bystander) ───────────────────────────────

    fun startScanning() {
        Log.d(TAG, "🔍 BLE scan starting — listening for nearby SOS")
        val options = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .setLowPower(false)
            .build()

        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                Log.i(TAG, "✅ BLE scan active — ready to receive SOS broadcasts")
                _isScanActive = true
            }
            .addOnFailureListener { e ->
                _isScanActive = false
                val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
                if (code == 8002) {
                    Log.d(TAG, "Already discovering — scan already active")
                    _isScanActive = true
                } else {
                    Log.e(TAG, "❌ BLE scan FAILED (code=$code): ${e.message}")
                }
            }
    }

    /** True while startDiscovery has succeeded and stopDiscovery has not been called. */
    var isScanActive: Boolean = false
        get() = _isScanActive
        private set
    private var _isScanActive = false

    fun stopScanning() {
        _isScanActive = false
        connectionsClient.stopDiscovery()
    }

    fun joinSession(endpointId: String, sessionId: String) {
        Log.d(TAG, "Joining SOS session $sessionId → connecting to $endpointId via Bluetooth")
        connectionsClient.requestConnection(SERVICE_ID, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { e -> Log.w(TAG, "joinSession failed: ${e.message}") }
    }

    // ── Task assignment logic ─────────────────────────────────────────────────

    fun assignTask(responderNumber: Int): String = when (responderNumber) {
        1    -> "Stay with them — guide verbally and keep calm"
        2    -> "Find an AED — check walls, reception areas"
        3    -> "Move to high ground and call 911 for signal"
        else -> "Keep bystanders back — give them space"
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    fun isNearbyAvailable(): Boolean = try {
        Nearby.getConnectionsClient(context); true
    } catch (e: Exception) { false }

    fun release() {
        runCatching { connectionsClient.stopAdvertising() }
        runCatching { connectionsClient.stopDiscovery() }
        runCatching { connectionsClient.stopAllEndpoints() }
        connectedEndpoints.clear()
        discoveredEndpoints.clear()
        Log.d(TAG, "MeshManager released")
    }

    // ── Encoding — compact JSON in the Nearby endpoint name field ────────────
    // The endpoint name is broadcast over BLE and visible without connecting.
    // This lets Person B's device decode the emergency type instantly.

    private fun encodeBroadcast(b: EmergencyBroadcast): String {
        // Compact pipe-separated format.
        // IMPORTANT: Nearby Connections endpoint name must stay under 100 bytes.
        // Format: v1|sev|type|summary|sessionIdShort|respondersNeeded|lat|lng|acc
        // All floats are explicitly formatted to control byte length.
        val lat = "%.5f".format(b.broadcasterLat)   // e.g. "37.77493"  — 8 chars
        val lng = "%.5f".format(b.broadcasterLng)   // e.g. "-122.41942" — 10 chars
        val acc = "%.1f".format(b.broadcasterAccuracy) // e.g. "15.2"   — 4 chars
        val summary = b.summary.take(25).replace("|", "")  // max 25 chars
        val encoded = "v1|${b.severity}|${b.type.take(12).replace("|", "")}|$summary|" +
               "${b.sessionId.take(8)}|${b.respondersNeeded}|$lat|$lng|$acc"

        Log.d(TAG, "Encoded SOS packet (len=${encoded.length}): $encoded")
        if (encoded.length > 95) Log.w(TAG, "⚠️ SOS packet is ${encoded.length} bytes — close to 100-byte limit!")
        return encoded
    }

    private fun decodeBroadcast(encoded: String): EmergencyBroadcast {
        Log.d(TAG, "Decoding incoming SOS: $encoded")
        if (!encoded.startsWith("v1|")) {
            // Fallback for old JSON format if any still exist in the air
            return try {
                val j = JSONObject(encoded)
                Log.d(TAG, "Fallback JSON parse successful")
                EmergencyBroadcast(
                    severity            = j.optInt("s", 3),
                    type                = j.optString("t", "other"),
                    summary             = j.optString("m", "Emergency assistance needed"),
                    sessionId           = j.optString("id", "unknown"),
                    respondersNeeded    = j.optInt("n", 2),
                    broadcasterLat      = j.optDouble("la", 0.0),
                    broadcasterLng      = j.optDouble("lo", 0.0),
                    broadcasterAccuracy = j.optDouble("ac", 0.0).toFloat(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "SOS parse failed: $encoded")
                throw Exception("Unknown SOS format: $encoded")
            }
        }
        val parts = encoded.split("|")
        val b = EmergencyBroadcast(
            severity            = parts.getOrNull(1)?.toIntOrNull() ?: 3,
            type                = parts.getOrNull(2) ?: "other",
            summary             = parts.getOrNull(3) ?: "Emergency assistance needed",
            sessionId           = parts.getOrNull(4) ?: "unknown",
            respondersNeeded    = parts.getOrNull(5)?.toIntOrNull() ?: 2,
            broadcasterLat      = parts.getOrNull(6)?.toDoubleOrNull() ?: 0.0,
            broadcasterLng      = parts.getOrNull(7)?.toDoubleOrNull() ?: 0.0,
            broadcasterAccuracy = parts.getOrNull(8)?.toFloatOrNull() ?: 0f,
        )
        Log.d(TAG, "SOS decoded successfully: ${b.type} from ${b.sessionId}")
        return b
    }
}
