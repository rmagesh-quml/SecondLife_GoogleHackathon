package com.secondlife.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.secondlife.MainActivity

object EmergencyNotificationManager {
    private const val ACTIVE_CHANNEL_ID = "secondlife_active"
    private const val ALERT_CHANNEL_ID  = "secondlife_alerts"
    private const val NOTIF_ID_ACTIVE = 1001
    private const val NOTIF_ID_ALERT  = 1002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            
            // 1. Persistent background scanning channel
            val activeChannel = NotificationChannel(
                ACTIVE_CHANNEL_ID,
                "SecondLife Active",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps SecondLife scanning for nearby emergencies"
                setShowBadge(false)
            }
            nm.createNotificationChannel(activeChannel)

            // 2. High-priority emergency alert channel
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Urgent SOS alerts from nearby people"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            nm.createNotificationChannel(alertChannel)
        }
    }

    fun showActive(context: Context) {
        show(context, "SecondLife Active", "Scanning for nearby emergencies", NOTIF_ID_ACTIVE, ACTIVE_CHANNEL_ID, true)
    }

    fun showBroadcasting(context: Context, responderCount: Int) {
        val text = if (responderCount == 0) "🔴 Broadcasting SOS…"
        else "🔴 Broadcasting emergency · $responderCount responders"
        show(context, "SecondLife Active", text, NOTIF_ID_ACTIVE, ACTIVE_CHANNEL_ID, true)
    }

    fun showAlert(context: Context, type: String, summary: String, endpointId: String) {
        // Create an intent that carries the endpoint ID
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.secondlife.intent.action.ACCEPT_EMERGENCY"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_AUTO_ACCEPT_ID", endpointId)
        }
        val pi = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 Nearby EMERGENCY: ${type.uppercase()}")
            .setContentText(summary)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // Long vibration pattern for background alerting
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500, 200, 500, 500, 1000))
            .setFullScreenIntent(pi, true) // Show as heads-up or open activity immediately
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID_ALERT, notif)
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_ACTIVE)
    }

    fun dismissAlert(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_ALERT)
    }

    private fun show(context: Context, title: String, text: String, id: Int, channel: String, ongoing: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(if (ongoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setOngoing(ongoing)
            .setContentIntent(pi)
            .setAutoCancel(!ongoing)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notif)
    }
}
