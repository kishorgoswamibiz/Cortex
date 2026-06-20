package com.cortex.pipeline

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Deterministic, offline relative-date parser. Pure `java.time` — no Android
 * dependencies, so it is JVM-unit-testable. It resolves the common grammar of
 * everyday reminders ("tomorrow", "next Tuesday", "in 2 weeks", "after a month",
 * "on the 5th", "July 3 at 5pm") against a supplied `now`.
 *
 * This is the auditable half of the dual-resolver design (Tech plan §New algorithms):
 * the flash LLM proposes ISO datetimes, this resolver independently computes them,
 * and [TemporalReconciler] reconciles the two. Because resolution is anchored to the
 * capture's own `now`, a note stays unambiguous when re-read a month later.
 */
object TemporalResolver {

    enum class TPrecision { EXACT, DAY, APPROX }

    data class ResolvedTemporal(
        val instant: Instant?,          // null = not resolvable deterministically
        val local: ZonedDateTime?,
        val precision: TPrecision,
        val matchedText: String,
        val hadExplicitTime: Boolean
    )

    private enum class Kind { EXPLICIT, MONTHDAY, NTH, WEEKDAY_NEXT, WEEKDAY_THIS, WEEKDAY_BARE, RELATIVE, APPROX }

    private data class DateMatch(val date: LocalDate, val precision: TPrecision, val kind: Kind, val matched: String)
    private data class TimeMatch(val time: LocalTime, val matched: String)

    private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY
    )

    private val MONTHS: Map<String, Int> = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9,
        "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12
    )

    private val WEEKDAY_ALT = WEEKDAYS.keys.sortedByDescending { it.length }.joinToString("|")
    private val MONTH_ALT = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")

    /** Find and resolve every temporal expression in [text], one per clause. */
    fun resolveAll(text: String, now: ZonedDateTime): List<ResolvedTemporal> {
        val clauses = text.lowercase().split(Regex("[,.;\\n]|\\band\\b|\\bthen\\b"))
        return clauses.mapNotNull { resolveClause(it, now) }
    }

    /** Resolve a single phrase (e.g. an LLM-supplied "next tuesday") deterministically. */
    fun resolvePhrase(phrase: String, now: ZonedDateTime): ResolvedTemporal? =
        resolveClause(phrase.lowercase(), now)

    private fun resolveClause(clause: String, now: ZonedDateTime): ResolvedTemporal? {
        val dateMatch = matchDate(clause, now)
        val timeMatch = matchTime(clause)
        if (dateMatch == null && timeMatch == null) return null

        val baseDate = dateMatch?.date ?: now.toLocalDate()
        val tonight = timeMatch == null && Regex("\\btonight\\b").containsMatchIn(clause)

        val localTime: LocalTime
        val precision: TPrecision
        when {
            timeMatch != null -> { localTime = timeMatch.time; precision = if (dateMatch?.precision == TPrecision.APPROX) TPrecision.APPROX else TPrecision.EXACT }
            tonight -> { localTime = LocalTime.of(20, 0); precision = TPrecision.DAY }
            else -> { localTime = LocalTime.of(9, 0); precision = if (dateMatch?.precision == TPrecision.APPROX) TPrecision.APPROX else TPrecision.DAY }
        }

        var zdt = ZonedDateTime.of(baseDate, localTime, now.zone)
        zdt = rollForward(zdt, now, dateMatch?.kind, hadExplicitTime = timeMatch != null)

        val matched = listOfNotNull(dateMatch?.matched, timeMatch?.matched)
            .joinToString(" ").trim()
            .ifBlank { clause.trim() }
        return ResolvedTemporal(zdt.toInstant(), zdt, precision, matched, timeMatch != null)
    }

    private fun matchDate(clause: String, now: ZonedDateTime): DateMatch? {
        val today = now.toLocalDate()

        if (Regex("\\bday after tomorrow\\b").containsMatchIn(clause))
            return DateMatch(today.plusDays(2), TPrecision.DAY, Kind.RELATIVE, "day after tomorrow")
        if (Regex("\\btomorrow\\b").containsMatchIn(clause))
            return DateMatch(today.plusDays(1), TPrecision.DAY, Kind.RELATIVE, "tomorrow")
        if (Regex("\\byesterday\\b").containsMatchIn(clause))
            return DateMatch(today.minusDays(1), TPrecision.DAY, Kind.RELATIVE, "yesterday")
        if (Regex("\\b(today|tonight)\\b").containsMatchIn(clause))
            return DateMatch(today, TPrecision.DAY, Kind.RELATIVE, "today")

        // "in N days/weeks/months"
        Regex("\\bin\\s+(\\d+)\\s+(day|week|month)s?\\b").find(clause)?.let { m ->
            return DateMatch(addUnit(today, m.groupValues[1].toLong(), m.groupValues[2]), TPrecision.DAY, Kind.RELATIVE, m.value.trim())
        }
        // "N weeks from now"
        Regex("\\b(\\d+)\\s+(day|week|month)s?\\s+from\\s+now\\b").find(clause)?.let { m ->
            return DateMatch(addUnit(today, m.groupValues[1].toLong(), m.groupValues[2]), TPrecision.DAY, Kind.RELATIVE, m.value.trim())
        }
        // "a/after a month|week" -> +1 unit
        Regex("\\b(?:after\\s+)?a\\s+(day|week|month)\\b").find(clause)?.let { m ->
            return DateMatch(addUnit(today, 1, m.groupValues[1]), TPrecision.DAY, Kind.RELATIVE, m.value.trim())
        }

        if (Regex("\\bnext weekend\\b").containsMatchIn(clause))
            return DateMatch(nextWeekday(today, DayOfWeek.SATURDAY, forceNext = true), TPrecision.APPROX, Kind.APPROX, "next weekend")
        if (Regex("\\bnext month\\b").containsMatchIn(clause))
            return DateMatch(today.plusMonths(1), TPrecision.APPROX, Kind.APPROX, "next month")
        if (Regex("\\bnext week\\b").containsMatchIn(clause))
            return DateMatch(today.plusWeeks(1), TPrecision.APPROX, Kind.APPROX, "next week")
        if (Regex("\\bend of (the )?month\\b").containsMatchIn(clause)) {
            val last = today.withDayOfMonth(today.lengthOfMonth())
            return DateMatch(last, TPrecision.APPROX, Kind.APPROX, "end of month")
        }

        // next/this <weekday>
        Regex("\\b(next|this)\\s+($WEEKDAY_ALT)\\b").find(clause)?.let { m ->
            val wd = WEEKDAYS.getValue(m.groupValues[2])
            return if (m.groupValues[1] == "next")
                DateMatch(nextWeekday(today, wd, forceNext = true), TPrecision.DAY, Kind.WEEKDAY_NEXT, m.value.trim())
            else
                DateMatch(nextWeekday(today, wd, forceNext = false), TPrecision.DAY, Kind.WEEKDAY_THIS, m.value.trim())
        }

        // "<month> <day>[, year]"
        Regex("\\b($MONTH_ALT)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:\\s*,?\\s*(\\d{4}))?\\b").find(clause)?.let { m ->
            return monthDay(MONTHS.getValue(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), today, m.value.trim())
        }
        // "<day> of <month>"
        Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+of\\s+($MONTH_ALT)\\b").find(clause)?.let { m ->
            return monthDay(MONTHS.getValue(m.groupValues[2]), m.groupValues[1].toInt(), null, today, m.value.trim())
        }
        // "on the Nth"
        Regex("\\bon the\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b").find(clause)?.let { m ->
            val day = m.groupValues[1].toInt().coerceIn(1, 31)
            val date = today.withDayOfMonth(day.coerceAtMost(today.lengthOfMonth()))
            return DateMatch(date, TPrecision.DAY, Kind.NTH, m.value.trim())
        }

        // bare weekday
        Regex("\\b($WEEKDAY_ALT)\\b").find(clause)?.let { m ->
            val wd = WEEKDAYS.getValue(m.groupValues[1])
            return DateMatch(nextWeekday(today, wd, forceNext = false), TPrecision.DAY, Kind.WEEKDAY_BARE, m.value.trim())
        }

        return null
    }

    private fun matchTime(clause: String): TimeMatch? {
        if (Regex("\\bnoon\\b").containsMatchIn(clause)) return TimeMatch(LocalTime.NOON, "noon")
        if (Regex("\\bmidnight\\b").containsMatchIn(clause)) return TimeMatch(LocalTime.MIDNIGHT, "midnight")

        Regex("\\b(\\d{1,2}):(\\d{2})\\s*(am|pm)?\\b").find(clause)?.let { m ->
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt(); val ap = m.groupValues[3]
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h in 0..23 && min in 0..59) return TimeMatch(LocalTime.of(h, min), m.value.trim())
        }
        Regex("\\b(\\d{1,2})\\s*(am|pm)\\b").find(clause)?.let { m ->
            var h = m.groupValues[1].toInt(); val ap = m.groupValues[2]
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h in 0..23) return TimeMatch(LocalTime.of(h, 0), m.value.trim())
        }
        // bare "at H" — daytime heuristic (at 5 -> 17:00)
        Regex("\\bat\\s+(\\d{1,2})\\b").find(clause)?.let { m ->
            val raw = m.groupValues[1].toInt()
            val h = when (raw) {
                in 1..7 -> raw + 12
                in 8..23 -> raw
                0 -> 0
                else -> return@let
            }
            return TimeMatch(LocalTime.of(h, 0), m.value.trim())
        }
        return null
    }

    private fun addUnit(from: LocalDate, n: Long, unit: String): LocalDate = when (unit) {
        "day" -> from.plusDays(n)
        "week" -> from.plusWeeks(n)
        else -> from.plusMonths(n) // calendar-aware: "after a month" lands same day-of-month next month
    }

    private fun nextWeekday(from: LocalDate, target: DayOfWeek, forceNext: Boolean): LocalDate {
        var ahead = (target.value - from.dayOfWeek.value + 7) % 7
        if (forceNext && ahead == 0) ahead = 7
        return from.plusDays(ahead.toLong())
    }

    private fun monthDay(month: Int, day: Int, year: Int?, today: LocalDate, matched: String): DateMatch {
        val y = year ?: today.year
        val safeDay = day.coerceIn(1, LocalDate.of(y, month, 1).lengthOfMonth())
        val date = LocalDate.of(y, month, safeDay)
        val kind = if (year != null) Kind.EXPLICIT else Kind.MONTHDAY
        return DateMatch(date, TPrecision.DAY, kind, matched)
    }

    private fun rollForward(zdt: ZonedDateTime, now: ZonedDateTime, kind: Kind?, hadExplicitTime: Boolean): ZonedDateTime {
        if (zdt.isAfter(now)) return zdt
        return when (kind) {
            Kind.WEEKDAY_BARE, Kind.WEEKDAY_THIS -> zdt.plusWeeks(1)
            Kind.MONTHDAY -> zdt.plusYears(1)
            Kind.NTH -> zdt.plusMonths(1)
            null -> if (hadExplicitTime) zdt.plusDays(1) else zdt // bare time already passed -> tomorrow
            else -> zdt // RELATIVE / EXPLICIT / WEEKDAY_NEXT / APPROX: leave as resolved
        }
    }
}
