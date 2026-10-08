package com.akash.aside

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.akash.aside.overlay.CaptureService

object Notifications {
    private const val CH_STATUS_OLD = "capture_status"
    // Android insists on a notification for a service that keeps the dot alive. It is posted to a channel that is
    // created blocked (IMPORTANCE_NONE), so it exists for the system but is never shown.
    private const val CH_STATUS = "capture_status_hidden"
    private const val CH_REMINDER = "capture_reminder"
    const val ID_STATUS = 1
    const val ID_REMINDER = 2

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(CH_STATUS_OLD)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Quick capture (keeps the dot alive, never shown)", NotificationManager.IMPORTANCE_NONE)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER, "Bring the dot back", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false); setSound(null, null); enableVibration(false) }
        )
    }

    private fun serviceIntent(ctx: Context, action: String, requestCode: Int, foreground: Boolean): PendingIntent {
        val i = Intent(ctx, CaptureService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (foreground) PendingIntent.getForegroundService(ctx, requestCode, i, flags)
        else PendingIntent.getService(ctx, requestCode, i, flags)
    }

    /** The notification Android requires while the dot is up. Tapping it opens the input window. */
    fun status(ctx: Context): Notification =
        Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_dot_plus)
            .setContentTitle("Quick capture is on")
            .setContentText("Tap to write")
            .setOngoing(true)
            .setContentIntent(serviceIntent(ctx, CaptureService.ACTION_OPEN, 10, foreground = false))
            .build()

    /** The one quiet nudge after the dot was hidden. Tapping it brings the dot back. */
    fun postReminder(ctx: Context) {
        val n = Notification.Builder(ctx, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_dot_plus)
            .setContentTitle("Quick capture is hidden")
            .setContentText("Tap to bring the dot back")
            .setAutoCancel(true)
            .setContentIntent(serviceIntent(ctx, CaptureService.ACTION_START, 12, foreground = true))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_REMINDER, n)
    }

    fun clearReminder(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_REMINDER)
    }
}
