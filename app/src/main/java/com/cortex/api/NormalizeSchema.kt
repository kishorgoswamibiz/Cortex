package com.cortex.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stage A ("Normalize & Understand") result. The flash model cleans up the raw
 * capture and resolves every temporal expression to an absolute ISO datetime
 * using the supplied NOW/ZONE, and flags reminder/task intent.
 */
@Serializable
data class NormalizeResult(
    @SerialName("clean_text") val cleanText: String = "",
    val temporals: List<TemporalExpr> = emptyList(),
    @SerialName("has_reminder_intent") val hasReminderIntent: Boolean = false,
    @SerialName("has_task_intent") val hasTaskIntent: Boolean = false,
    @SerialName("reminder_title") val reminderTitle: String? = null
)

@Serializable
data class TemporalExpr(
    val phrase: String = "",                  // verbatim substring of the ORIGINAL capture
    val iso: String? = null,                  // e.g. "2026-06-23T17:00:00+05:30"; null if unresolvable
    val precision: String = "approx",         // exact | day | approx
    val kind: String = "datetime",            // datetime | date | time | duration | recurring
    val rrule: String? = null                 // recurrence hint only; not scheduled in v2
)
