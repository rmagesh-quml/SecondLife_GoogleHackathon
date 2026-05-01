package com.secondlife.mesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class MeshManager(
    private val context: Context,
    private val onEmergencyReceived: (EmergencyBroadcast, String) -> Unit,
    private val onResponderJoined: (String, Int) -> Unit,
    private val onTaskReceived: (String) -> Unit,
    private val onRSSIUpdate: (Int) -> Unit,
) {
    private val TAG = "MeshManager"
    private val SERVICE_ID = "com.secondlife.emergency"
    private val STRATEGY = Strategy.P2P_CLUSTER

    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }

    // Track active responder endpoints (broadcaster side)
    private val connectedEndpoints = mutableListOf<String>()
    // Track discovered endpoints (receiver side): endpointId -> broadcast
    private val discoveredEndpoints = mutableMapOf<String, EmergencyBroadcast>()

    data class EmergencyBroadcast(
        val severity: Int,
        val type: String,
        val summary: String,
        val sessionId: String,
        val respondersNeeded: Int,
        val broadcasterLat: Double,
        val broadcasterLng: Double,
        val broadcasterAccuracy: Float,
    )

    data class SessionContext(
        val broadcast: EmergencyBroadcast,
        val fullGuidance: String,
        val assignedTask: String,
    )

    // ── Connection lifecycle callbacks ────────────────────────────────────────

    private val connectionLifecycleCallback: ConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept all connections from other SecondLife instances
            Log.d(TAG, "Connection initiated from $endpointId — auto-accepting")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e -> Log.w(TAG, "acceptConnection failed: ${e.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d(TAG, "✅ Connected to $endpointId")
                    if (!connectedEndpoints.contains(endpointId)) {
                        connectedEndpoints.add(endpointId)
                        onResponderJoined(endpointId, connectedEndpoints.size)
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.w(TAG, "Connection rejected by $endpointId")
                }
                else -> {
                    Log.w(TAG, "Connection failed to $endpointId (${result.status.statusCode}) — retrying once")
                    // Retry once after 2s
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        connectionsClient.requestConnection(SERVICE_ID, endpointId, connectionLifecycleCallback)
                            .addOnFailureListener { Log.w(TAG, "Retry also failed for $endpointId") }
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

    // ── Endpoint discovery callbacks ──────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Found endpoint $endpointId (serviceId=${info.serviceId})")
            if (info.serviceId != SERVICE_ID) return

            // Decode the broadcast packet from the endpoint name field
            runCatching {
                val broadcast = decodeBroadcast(info.endpointName)
                discoveredEndpoints[endpointId] = broadcast
                onEmergencyReceived(broadcast, endpointId)
                // Rough RSSI simulation — Nearby doesn't expose raw RSSI directly
                onRSSIUpdate(-65)
            }.onFailure { e ->
                Log.w(TAG, "Failed to decode broadcast from $endpointId: ${e.message}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost endpoint $endpointId")
            discoveredEndpoints.remove(endpointId)
        }
    }

    // ── Payload callbacks ─────────────────────────────────────────────────────

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val text = String(bytes, StandardCharsets.UTF_8)
            Log.d(TAG, "Payload from $endpointId: ${text.take(80)}")

            when {
                text.startsWith("TASK:") -> onTaskReceived(text.removePrefix("TASK:"))
                text.startsWith("CTX:")  -> {
                    runCatching {
                        val json = JSONObject(text.removePrefix("CTX:"))
                        val task = json.optString("assignedTask", "Assist the primary responder")
                        onTaskReceived(task)
                    }.onFailure { Log.w(TAG, "Failed to parse CTX payload: ${it.message}") }
                }
                text.startsWith("RSSI:") -> {
                    runCatching { onRSSIUpdate(text.removePrefix("RSSI:").trim().toInt()) }
                        .onFailure { }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                Log.d(TAG, "Payload transfer to $endpointId complete")
            }
        }
    }

    // ── Broadcaster side ──────────────────────────────────────────────────────

    fun startBroadcasting(broadcast: EmergencyBroadcast) {
        Log.d(TAG, "📡 Broadcasting emergency: ${broadcast.type}")
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        // Encode the broadcast as the endpoint name (max ~130 bytes in Nearby)
        val encodedName = encodeBroadcast(broadcast)

        connectionsClient.startAdvertising(
            encodedName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions,
        ).addOnSuccessListener {
            Log.d(TAG, "✅ Advertising started")
        }.addOnFailureListener { e ->
            Log.w(TAG, "Advertising failed: ${e.message}")
        }
    }

    fun stopBroadcasting() {
        Log.d(TAG, "Stopping advertising")
        connectionsClient.stopAdvertising()
        connectedEndpoints.toList().forEach { endpointId ->
            connectionsClient.disconnectFromEndpoint(endpointId)
        }
        connectedEndpoints.clear()
    }

    fun sendTaskAssignment(endpointId: String, task: String) {
        val payload = Payload.fromBytes("TASK:$task".toByteArray(StandardCharsets.UTF_8))
        connectionsClient.sendPayload(endpointId, payload)
            .addOnFailureListener { e -> Log.w(TAG, "sendTask failed: ${e.message}") }
    }

    fun sendSessionContext(endpointId: String, ctx: SessionContext) {
        val json = JSONObject().apply {
            put("severity", ctx.broadcast.severity)
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

    // ── Receiver side ─────────────────────────────────────────────────────────

    fun startScanning() {
        Log.d(TAG, "🔍 Scanning for nearby emergencies")
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { Log.d(TAG, "✅ Discovery started") }
            .addOnFailureListener { e -> Log.w(TAG, "Discovery failed (may already be running): ${e.message}") }
    }

    fun stopScanning() {
        connectionsClient.stopDiscovery()
    }

    fun joinSession(endpointId: String, sessionId: String) {
        Log.d(TAG, "Joining session $sessionId via $endpointId")
        connectionsClient.requestConnection(
            SERVICE_ID,
            endpointId,
            connectionLifecycleCallback,
        ).addOnFailureListener { e ->
            Log.w(TAG, "joinSession failed: ${e.message}")
        }
    }

    // ── Task assignment ───────────────────────────────────────────────────────

    fun assignTask(responderNumber: Int): String = when (responderNumber) {
        1    -> "Guide the primary responder verbally"
        2    -> "Locate an AED — check nearby walls"
        3    -> "Call 911 the moment you have signal"
        else -> "Clear the area, keep bystanders back"
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    fun isNearbyAvailable(): Boolean = try {
        Nearby.getConnectionsClient(context)
        true
    } catch (e: Exception) {
        false
    }

    fun release() {
        Log.d(TAG, "Releasing MeshManager")
        runCatching { connectionsClient.stopAdvertising() }
        runCatching { connectionsClient.stopDiscovery() }
        runCatching { connectionsClient.stopAllEndpoints() }
        connectedEndpoints.clear()
        discoveredEndpoints.clear()
    }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    private fun encodeBroadcast(b: EmergencyBroadcast): String {
        // Compact JSON — keep under 130 chars for endpoint name limit
        return JSONObject().apply {
            put("s", b.severity)
            put("t", b.type)
            put("m", b.summary.take(40))
            put("id", b.sessionId.take(8))
            put("n", b.respondersNeeded)
            put("la", b.broadcasterLat)
            put("lo", b.broadcasterLng)
            put("ac", b.broadcasterAccuracy)
        }.toString()
    }

    private fun decodeBroadcast(encoded: String): EmergencyBroadcast {
        val j = JSONObject(encoded)
        return EmergencyBroadcast(
            severity          = j.optInt("s", 3),
            type              = j.optString("t", "other"),
            summary           = j.optString("m", "Emergency assistance needed"),
            sessionId         = j.optString("id", "unknown"),
            respondersNeeded  = j.optInt("n", 2),
            broadcasterLat    = j.optDouble("la", 0.0),
            broadcasterLng    = j.optDouble("lo", 0.0),
            broadcasterAccuracy = j.optDouble("ac", 0.0).toFloat(),
        )
    }
}
