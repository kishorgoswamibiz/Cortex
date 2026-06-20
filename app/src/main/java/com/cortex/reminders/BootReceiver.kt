package com.cortex.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cortex.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Exact alarms do not survive a reboot, so re-schedule all future scheduled
 * reminders when the device finishes booting.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val dao = AppDatabase.getDatabase(context).cortexDao()
        val scheduler = ReminderScheduler(context)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                dao.getScheduledFutureReminders(System.currentTimeMillis()).forEach { scheduler.schedule(it) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
