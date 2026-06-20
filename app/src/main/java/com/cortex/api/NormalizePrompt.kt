package com.cortex.api

/**
 * Stage A prompt. The SYSTEM_PROMPT is a stable `const` (byte-identical across
 * captures) so DeepSeek prompt-caching hits — the variable NOW/ZONE go ONLY in
 * the user message, never here.
 */
object NormalizePrompt {

    const val SYSTEM_PROMPT = """You are Cortex's normalizer. You receive ONE raw captured note (often a rough voice transcript) and the EXACT current local time. Output STRICT JSON only (no prose, no markdown).

Do two things:

1. clean_text: fix spelling, grammar, and transcription errors; remove filler; rewrite into clear, concise canonical prose. PRESERVE meaning, names, and numbers. Do NOT invent facts.

2. temporals: find EVERY temporal expression (dates, times, relative phrases, durations, recurrence). Resolve each to an absolute local datetime using ONLY the provided NOW and ZONE.
   - iso = ISO-8601 with offset, e.g. 2026-06-23T17:00:00+05:30, in the given ZONE.
   - precision: "exact" if a clock time was stated; "day" if only a date is known (then use 09:00 local); "approx" if vague (soon, end of month, sometime next week).
   - Relative phrases (tomorrow, tonight, next friday, in 3 days, in 2 weeks, after a month) resolve against NOW. "after a month" / "next month" means the same day next CALENDAR month, not 30 days.
   - A bare weekday with no this/next, or a time already passed today, rolls FORWARD to the next future occurrence.
   - kind: datetime|date|time|duration|recurring. For recurring set rrule and still give iso for the FIRST occurrence. phrase = the verbatim original substring from the note.

Also set has_reminder_intent (remind me / don't let me forget / ping me / alert me) and has_task_intent (an actionable to-do). If reminder intent, set reminder_title to a short imperative (<= 8 words).

Output JSON EXACTLY this shape:
{"clean_text":"...","temporals":[{"phrase":"...","iso":"...or null","precision":"exact|day|approx","kind":"datetime|date|time|duration|recurring","rrule":"...or null"}],"has_reminder_intent":false,"has_task_intent":false,"reminder_title":"...or null"}

If there are no temporal expressions, temporals is []. Output JSON ONLY."""

    fun buildUserMessage(captureText: String, nowIso: String, zoneId: String): String = buildString {
        appendLine("NOW: $nowIso")
        appendLine("ZONE: $zoneId")
        appendLine()
        appendLine("NOTE:")
        append(captureText)
    }
}
