package fr.locationsender.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import fr.locationsender.MainActivity
import fr.locationsender.R

/** Notifications persistantes des services foreground (réouvrent l'app au tap). */
object Notifications {
    const val CHANNEL_ID = "ls_service"
    const val SENDER_NOTIF_ID = 1001
    const val RECEIVER_NOTIF_ID = 1002

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Service Location Sender",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Notification persistante du service" }
            mgr.createNotificationChannel(channel)
        }
    }

    fun build(context: Context, title: String, text: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
