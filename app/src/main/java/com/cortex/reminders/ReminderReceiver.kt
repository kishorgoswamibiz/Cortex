package com.cortex.reminders

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cortex.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Receives the exact-alarm broadcast and posts the reminder notification, or
 * handles the Done / Snooze actions from a posted notification.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Notifications.EXTRA_ID) ?: return
        val title = intent.getStringExtra(Notifications.EXTRA_TITLE) ?: "Reminder"
        val body = intent.getStringExtra(Notifications.EXTRA_BODY)
        val dao = AppDatabase.getDatabase(context).cortexDao()
        val scheduler = ReminderScheduler(context)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Notifications.ACTION_DONE -> {
                        dao.markReminderStatus(id, "done", System.currentTimeMillis())
                        cancelNotification(context, id)
                    }
                    Notifications.ACTION_SNOOZE -> {
                        val next = System.currentTimeMillis() + Duration.ofMinutes(Notifications.SNOOZE_MINUTES).toMillis()
                        dao.updateReminderTrigger(id, next)
                        dao.getReminderById(id)?.let { scheduler.schedule(it) }
                        cancelNotification(context, id)
                    }
                    else -> { // ACTION_FIRE
                        postNotification(context, id, title, body)
                        dao.markReminderStatus(id, "fired", System.currentTimeMillis())
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postNotification(context: Context, id: String, title: String, body: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return // user hasn't granted notifications; nothing to post
        NotificationManagerCompat.from(context)
            .notify(Notifications.notificationIdFor(id), Notifications.build(context, id, title, body))
    }

    private fun cancelNotification(context: Context, id: String) {
        NotificationManagerCompat.from(context).cancel(Notifications.notificationIdFor(id))
    }
}
