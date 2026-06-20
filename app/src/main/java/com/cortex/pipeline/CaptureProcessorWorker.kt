package com.cortex.pipeline

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cortex.BuildConfig
import com.cortex.api.ChatCompletionRequest
import com.cortex.api.DeepSeekClient
import com.cortex.api.ExtractionPrompt
import com.cortex.api.ExtractionResult
import com.cortex.api.Message
import com.cortex.api.ResponseFormat
import com.cortex.data.AppDatabase
import com.cortex.data.CaptureEntity
import com.cortex.data.CortexDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class CaptureProcessorWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(applicationContext)
        val dao = db.cortexDao()
        val embeddings = EmbeddingService.get(applicationContext)
        val resolver = EntityResolver(dao, embeddings)
        val applier = ExtractionApplier(dao, resolver, embeddings)

        val pending = dao.getPendingCaptures()
        if (pending.isEmpty()) return@withContext Result.success()

        if (BuildConfig.DEEPSEEK_API_KEY.isBlank()) {
            Log.w(TAG, "No DEEPSEEK_API_KEY configured; leaving captures pending.")
            return@withContext Result.retry()
        }
        val authHeader = "Bearer ${BuildConfig.DEEPSEEK_API_KEY}"

        var anyFailedTransient = false
        for (capture in pending) {
            try {
                processOne(capture, dao, applier, authHeader)
            } catch (t: Throwable) {
                Log.e(TAG, "Capture ${capture.id} failed: ${t.message}", t)
                // Mark failed so we don't loop forever on a single broken capture;
                // a future "retry failed" UI can re-queue it (FR-7.4).
                dao.updateCaptureStatus(capture.id, "failed", System.currentTimeMillis())
                anyFailedTransient = true
            }
        }
        if (anyFailedTransient) Result.retry() else Result.success()
    }

    private suspend fun processOne(
        capture: CaptureEntity,
        dao: CortexDao,
        applier: ExtractionApplier,
        authHeader: String
    ) {
        val candidates = CandidateRetrieval.retrieve(dao, capture.rawText)

        val req = ChatCompletionRequest(
            model = "deepseek-chat",
            messages = listOf(
                Message("system", ExtractionPrompt.SYSTEM_PROMPT),
                Message("user", ExtractionPrompt.buildUserMessage(capture.rawText, candidates))
            ),
            responseFormat = ResponseFormat(type = "json_object")
        )

        val response = DeepSeekClient.api.createCompletion(authHeader, req)
        val rawJson = response.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("DeepSeek returned no content")

        val parsed = parseExtraction(rawJson)
            ?: parseExtraction(retryWithCorrection(authHeader, capture, candidates, rawJson))
            ?: throw IllegalStateException("Extraction JSON malformed after retry")

        val outcome = applier.apply(capture.id, parsed, candidates)
        Log.i(TAG, "Capture ${capture.id} -> items=${outcome.itemsWritten} newNodes=${outcome.nodesCreated} edges=${outcome.edgesCreated} confirm=${outcome.confirmationsNeeded}")
    }

    private suspend fun retryWithCorrection(
        authHeader: String,
        capture: CaptureEntity,
        candidates: List<com.cortex.api.CandidateNode>,
        previousReply: String
    ): String {
        val req = ChatCompletionRequest(
            model = "deepseek-chat",
            messages = listOf(
                Message("system", ExtractionPrompt.SYSTEM_PROMPT),
                Message("user", ExtractionPrompt.buildUserMessage(capture.rawText, candidates)),
                Message("assistant", previousReply),
                Message("user", "Your previous reply was not valid JSON for the required schema. Reply again with ONLY a valid JSON object matching the schema. No prose.")
            ),
            responseFormat = ResponseFormat(type = "json_object")
        )
        val resp = DeepSeekClient.api.createCompletion(authHeader, req)
        return resp.choices.firstOrNull()?.message?.content ?: ""
    }

    private fun parseExtraction(raw: String?): ExtractionResult? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSON.decodeFromString(ExtractionResult.serializer(), raw)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse ExtractionResult: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "CaptureProcessor"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    }
}
