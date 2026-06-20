package com.cortex.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.cortex.data.ReminderEntity

/**
 * Schedules exact alarms for reminders. Uses setExactAndAllowWhileIdle so the
 * notification fires at the precise minute even in Doze; gracefully degrades to
 * an inexact allow-while-idle alarm if the exact-alarm permission is revoked.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    fun schedule(reminder: ReminderEntity) {
        val am = alarmManager ?: return
        val pi = firePendingIntent(reminder.id, reminder.title, reminder.body)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pi)
            } else {
                Log.w(TAG, "Exact alarms not permitted; using inexact for ${reminder.id}")
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pi)
            }
        } catch (sec: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pi)
        }
    }

    fun cancel(reminderId: String) {
        alarmManager?.cancel(firePendingIntent(reminderId, "", null))
    }

    private fun firePendingIntent(id: String, title: String, body: String?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = Notifications.ACTION_FIRE
            putExtra(Notifications.EXTRA_ID, id)
            putExtra(Notifications.EXTRA_TITLE, title)
            putExtra(Notifications.EXTRA_BODY, body)
        }
        return PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object { private const val TAG = "ReminderScheduler" }
}
