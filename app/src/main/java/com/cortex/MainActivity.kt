package com.cortex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.cortex.capture.SpeechManager
import com.cortex.data.AppDatabase
import com.cortex.data.CaptureEntity
import com.cortex.pipeline.CaptureProcessorWorker
import com.cortex.ui.AppShell
import com.cortex.ui.theme.CortexTheme
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var speechManager: SpeechManager
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        speechManager = SpeechManager(this)
        db = AppDatabase.getDatabase(this)

        setContent {
            CortexTheme {
                AppShell(
                    speechManager = speechManager,
                    onProcessCapture = { text -> enqueueCapture(text) }
                )
            }
        }
    }

    private fun enqueueCapture(text: String) {
        if (text.isBlank()) return
        lifecycleScope.launch {
            val capture = CaptureEntity(
                id = UUID.randomUUID().toString(),
                rawText = text,
                source = "voice",
                status = "pending",
                createdAt = System.currentTimeMillis(),
                processedAt = null
            )
            db.cortexDao().insertCapture(capture)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val workRequest = OneTimeWorkRequestBuilder<CaptureProcessorWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(this@MainActivity)
                .enqueueUniqueWork(
                    "capture_processor",
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    workRequest
                )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.destroy()
    }
}
