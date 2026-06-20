package com.cortex.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Notification channel + builder for fired reminders, with Done / Snooze actions.
 */
object Notifications {
    const val CHANNEL_ID = "cortex_reminders"
    const val ACTION_FIRE = "com.cortex.reminders.FIRE"
    const val ACTION_DONE = "com.cortex.reminders.DONE"
    const val ACTION_SNOOZE = "com.cortex.reminders.SNOOZE"
    const val EXTRA_ID = "reminder_id"
    const val EXTRA_TITLE = "reminder_title"
    const val EXTRA_BODY = "reminder_body"
    const val SNOOZE_MINUTES = 10L

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Cortex reminders and to-dos"
                enableVibration(true)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    fun build(context: Context, id: String, title: String, body: String?): android.app.Notification {
        ensureChannel(context)
        val done = actionIntent(context, ACTION_DONE, id, title, body, id.hashCode() + 1)
        val snooze = actionIntent(context, ACTION_SNOOZE, id, title, body, id.hashCode() + 2)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .apply { if (!body.isNullOrBlank()) setContentText(body) }
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(0, "Done", done)
            .addAction(0, "Snooze ${SNOOZE_MINUTES}m", snooze)
            .build()
    }

    private fun actionIntent(
        context: Context,
        action: String,
        id: String,
        title: String,
        body: String?,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun notificationIdFor(id: String): Int = id.hashCode()
}
