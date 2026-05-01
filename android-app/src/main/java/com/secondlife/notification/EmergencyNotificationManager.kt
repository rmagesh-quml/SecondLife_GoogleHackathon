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
    private const val CHANNEL_ID = "secondlife_active"
    private const val NOTIF_ID = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SecondLife Active",
                NotificationManager.IMPORTANCE_LOW,  // not intrusive
            ).apply {
                description = "Keeps SecondLife scanning for nearby emergencies"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun showActive(context: Context) {
        show(context, "SecondLife Active", "Scanning for nearby emergencies")
    }

    fun showBroadcasting(context: Context, responderCount: Int) {
        val text = if (responderCount == 0) "🔴 Broadcasting SOS…"
        else "🔴 Broadcasting emergency · $responderCount responders"
        show(context, "SecondLife Active", text)
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    private fun show(context: Context, title: String, text: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .setAutoCancel(false)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }
}
