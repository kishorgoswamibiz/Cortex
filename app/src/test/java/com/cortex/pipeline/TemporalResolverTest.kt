package com.cortex.pipeline

import com.cortex.pipeline.TemporalResolver.TPrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TemporalResolverTest {

    // Fixed reference: Sat 2026-06-20 14:00 in Asia/Kolkata.
    private val now = ZonedDateTime.of(2026, 6, 20, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    private fun resolve(p: String) = TemporalResolver.resolvePhrase(p, now)

    @Test fun tomorrow_is_next_day_at_9am_day_precision() {
        val r = resolve("tomorrow")!!
        assertEquals(now.toLocalDate().plusDays(1), r.local!!.toLocalDate())
        assertEquals(LocalTime.of(9, 0), r.local!!.toLocalTime())
        assertEquals(TPrecision.DAY, r.precision)
    }

    @Test fun in_two_weeks_adds_14_days() {
        val r = resolve("in 2 weeks")!!
        assertEquals(now.toLocalDate().plusDays(14), r.local!!.toLocalDate())
    }

    @Test fun two_weeks_from_now_adds_14_days() {
        val r = resolve("2 weeks from now")!!
        assertEquals(now.toLocalDate().plusDays(14), r.local!!.toLocalDate())
    }

    @Test fun after_a_month_is_calendar_month_not_30_days() {
        val r = resolve("after a month")!!
        assertEquals(now.toLocalDate().plusMonths(1), r.local!!.toLocalDate())
    }

    @Test fun next_tuesday_is_a_future_tuesday() {
        val r = resolve("next tuesday")!!
        assertEquals(DayOfWeek.TUESDAY, r.local!!.dayOfWeek)
        assertTrue(r.instant!!.isAfter(now.toInstant()))
    }

    @Test fun explicit_time_gives_exact_precision() {
        val r = resolve("at 5pm")!!
        assertEquals(17, r.local!!.hour)
        assertEquals(TPrecision.EXACT, r.precision)
        assertTrue(r.instant!!.isAfter(now.toInstant())) // 5pm today is still ahead of 2pm
    }

    @Test fun bare_time_already_passed_rolls_to_tomorrow() {
        val r = resolve("at 9am")!! // 9am < 2pm now -> tomorrow
        assertEquals(now.toLocalDate().plusDays(1), r.local!!.toLocalDate())
        assertEquals(9, r.local!!.hour)
    }

    @Test fun month_day_with_time() {
        val r = resolve("july 3 at 5pm")!!
        assertEquals(7, r.local!!.monthValue)
        assertEquals(3, r.local!!.dayOfMonth)
        assertEquals(17, r.local!!.hour)
        assertEquals(TPrecision.EXACT, r.precision)
    }

    @Test fun month_day_in_past_rolls_to_next_year() {
        val r = resolve("january 5")!! // Jan 5 already passed in 2026
        assertEquals(2027, r.local!!.year)
        assertEquals(1, r.local!!.monthValue)
    }

    @Test fun on_the_25th_this_month() {
        val r = resolve("on the 25th")!! // 25 > 20, still this month
        assertEquals(25, r.local!!.dayOfMonth)
        assertEquals(6, r.local!!.monthValue)
    }

    @Test fun end_of_month_is_approx_last_day() {
        val r = resolve("end of the month")!!
        assertEquals(30, r.local!!.dayOfMonth) // June has 30 days
        assertEquals(TPrecision.APPROX, r.precision)
    }

    @Test fun tonight_defaults_to_8pm() {
        val r = resolve("tonight")!!
        assertEquals(now.toLocalDate(), r.local!!.toLocalDate())
        assertEquals(20, r.local!!.hour)
    }

    @Test fun non_temporal_text_returns_null() {
        assertNull(resolve("buy groceries and call the bank"))
    }

    @Test fun resolveAll_finds_multiple_anchors() {
        val anchors = TemporalResolver.resolveAll("call Ramesh tomorrow, and submit report next friday", now)
        assertTrue(anchors.size >= 2)
    }
}
