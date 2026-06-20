package com.cortex.pipeline

import com.cortex.api.TemporalExpr
import com.cortex.pipeline.TemporalResolver.TPrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class TemporalReconcilerTest {

    private val now = ZonedDateTime.of(2026, 6, 20, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    private fun isoFor(phrase: String): String =
        TemporalResolver.resolvePhrase(phrase, now)!!.local!!.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    @Test fun llm_and_deterministic_agree_no_flag() {
        val t = TemporalExpr(phrase = "tomorrow", iso = isoFor("tomorrow"), precision = "day")
        val anchors = TemporalReconciler.reconcile(listOf(t), "do it tomorrow", now)
        assertEquals(1, anchors.size)
        assertNull(anchors[0].flag)
    }

    @Test fun disagreement_deterministic_wins_and_flags_conflict() {
        // LLM hallucinates a wrong date for "tomorrow"
        val wrong = now.plusDays(10).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val t = TemporalExpr(phrase = "tomorrow", iso = wrong, precision = "day")
        val anchors = TemporalReconciler.reconcile(listOf(t), "do it tomorrow", now)
        assertEquals(1, anchors.size)
        assertEquals("RECONCILE_CONFLICT", anchors[0].flag)
        // deterministic value (tomorrow) wins
        assertEquals(now.toLocalDate().plusDays(1), anchors[0].instant.atZone(now.zone).toLocalDate())
    }

    @Test fun offline_no_llm_uses_deterministic_only() {
        val anchors = TemporalReconciler.reconcile(emptyList(), "remind me next friday", now)
        assertTrue(anchors.isNotEmpty())
        assertEquals("DET_ONLY", anchors[0].flag)
    }

    @Test fun llm_only_when_deterministic_cannot_parse() {
        // a phrase the deterministic resolver doesn't understand, but LLM resolved
        val iso = now.plusDays(3).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val t = TemporalExpr(phrase = "the day after the launch", iso = iso, precision = "day")
        val anchors = TemporalReconciler.reconcile(listOf(t), "ship the day after the launch", now)
        assertEquals(1, anchors.size)
        assertEquals("LLM_ONLY", anchors[0].flag)
    }

    @Test fun primary_picks_earliest_future_anchor() {
        val anchors = TemporalReconciler.reconcile(
            emptyList(),
            "call Ramesh next friday and submit report tomorrow",
            now
        )
        val primary = TemporalReconciler.primary(anchors, now)!!
        // tomorrow is earlier than next friday
        assertEquals(now.toLocalDate().plusDays(1), primary.instant.atZone(now.zone).toLocalDate())
    }
}
