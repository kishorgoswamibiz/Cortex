package com.cortex.pipeline

import android.util.Log
import com.cortex.api.ChatCompletionRequest
import com.cortex.api.DeepSeekClient
import com.cortex.api.Message
import com.cortex.api.NormalizePrompt
import com.cortex.api.NormalizeResult
import com.cortex.api.ResponseFormat
import com.cortex.pipeline.TemporalReconciler.ResolvedAnchor
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Stage A of the two-stage pipeline. Calls the flash model to clean the capture
 * and resolve temporal expressions, then reconciles those against the
 * deterministic [TemporalResolver]. Degrades gracefully: if the LLM call fails
 * (offline), the deterministic resolver still produces anchors for common dates.
 */
class NormalizeService(private val authHeader: String) {

    data class Normalized(
        val cleanText: String,
        val anchors: List<ResolvedAnchor>,
        val primary: ResolvedAnchor?,
        val hasReminderIntent: Boolean,
        val hasTaskIntent: Boolean,
        val reminderTitle: String?
    )

    suspend fun run(rawText: String, now: ZonedDateTime): Normalized {
        val nowIso = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val llm = try {
            callLlm(rawText, nowIso, now.zone.id)
        } catch (t: Throwable) {
            Log.w(TAG, "Stage A normalize failed; deterministic-only. ${t.message}")
            null
        }

        val cleanText = llm?.cleanText?.takeIf { it.isNotBlank() } ?: rawText
        val anchors = TemporalReconciler.reconcile(llm?.temporals ?: emptyList(), rawText, now)
        val primary = TemporalReconciler.primary(anchors, now)

        return Normalized(
            cleanText = cleanText,
            anchors = anchors,
            primary = primary,
            hasReminderIntent = llm?.hasReminderIntent ?: false,
            hasTaskIntent = llm?.hasTaskIntent ?: false,
            reminderTitle = llm?.reminderTitle
        )
    }

    private suspend fun callLlm(rawText: String, nowIso: String, zoneId: String): NormalizeResult {
        val req = ChatCompletionRequest(
            model = DeepSeekClient.MODEL,
            messages = listOf(
                Message("system", NormalizePrompt.SYSTEM_PROMPT),
                Message("user", NormalizePrompt.buildUserMessage(rawText, nowIso, zoneId))
            ),
            responseFormat = ResponseFormat(type = "json_object")
        )
        val resp = DeepSeekClient.api.createCompletion(authHeader, req)
        val raw = resp.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("Stage A returned no content")
        return JSON.decodeFromString(NormalizeResult.serializer(), raw)
    }

    companion object {
        private const val TAG = "NormalizeService"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    }
}
