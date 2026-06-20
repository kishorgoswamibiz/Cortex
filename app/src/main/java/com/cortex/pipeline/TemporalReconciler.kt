package com.cortex.pipeline

import com.cortex.api.TemporalExpr
import com.cortex.pipeline.TemporalResolver.TPrecision
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Reconciles the flash LLM's resolved datetimes against the deterministic
 * [TemporalResolver] (the adversarial cross-check). On disagreement the testable
 * deterministic result wins and the anchor is demoted + flagged, so a wrong
 * *exact* alarm never fires. Works offline (deterministic-only path).
 */
object TemporalReconciler {

    data class ResolvedAnchor(
        val instant: Instant,
        val precision: TPrecision,
        val phrase: String,
        val flag: String?            // RECONCILE_CONFLICT | LLM_ONLY | DET_ONLY | null
    )

    /**
     * @param llmTemporals temporals from Stage A (may be empty when offline)
     * @param rawText the original capture (so we can catch anchors the LLM missed)
     */
    fun reconcile(
        llmTemporals: List<TemporalExpr>,
        rawText: String,
        now: ZonedDateTime
    ): List<ResolvedAnchor> {
        val anchors = mutableListOf<ResolvedAnchor>()
        val covered = mutableListOf<Instant>()

        // 1) Each LLM temporal, reconciled with a deterministic re-parse of its phrase.
        for (t in llmTemporals) {
            val llmInstant = parseIso(t.iso)
            val llmPrec = parsePrecision(t.precision)
            val det = TemporalResolver.resolvePhrase(t.phrase, now)
            val detInstant = det?.instant

            val anchor: ResolvedAnchor? = when {
                llmInstant != null && detInstant != null -> {
                    val detPrec = det.precision
                    when {
                        agree(llmInstant, detInstant, minPrecision(llmPrec, detPrec), now) ->
                            ResolvedAnchor(detInstant, minPrecision(llmPrec, detPrec), t.phrase, null)
                        // LLM caught a clock time the regex missed, same day -> trust LLM time.
                        llmPrec == TPrecision.EXACT && detPrec != TPrecision.EXACT &&
                            sameLocalDate(llmInstant, detInstant, now) ->
                            ResolvedAnchor(llmInstant, TPrecision.EXACT, t.phrase, null)
                        else ->
                            ResolvedAnchor(detInstant, TPrecision.DAY, t.phrase, "RECONCILE_CONFLICT")
                    }
                }
                llmInstant != null -> ResolvedAnchor(llmInstant, llmPrec, t.phrase, "LLM_ONLY")
                detInstant != null -> ResolvedAnchor(detInstant, det.precision, t.phrase, "DET_ONLY")
                else -> null
            }
            if (anchor != null && covered.none { closeEnough(it, anchor.instant) }) {
                anchors.add(anchor)
                covered.add(anchor.instant)
            }
        }

        // 2) Deterministic-only sweep of the raw text for anchors the LLM didn't report
        //    (offline safety, and missed expressions).
        for (det in TemporalResolver.resolveAll(rawText, now)) {
            val inst = det.instant ?: continue
            if (covered.none { closeEnough(it, inst) }) {
                anchors.add(ResolvedAnchor(inst, det.precision, det.matchedText, "DET_ONLY"))
                covered.add(inst)
            }
        }

        return anchors
    }

    /** Primary anchor = earliest FUTURE anchor; else the first anchor we have. */
    fun primary(anchors: List<ResolvedAnchor>, now: ZonedDateTime): ResolvedAnchor? {
        val nowInstant = now.toInstant()
        return anchors.filter { it.instant.isAfter(nowInstant) }.minByOrNull { it.instant }
            ?: anchors.firstOrNull()
    }

    private fun parseIso(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(iso).toInstant()
        } catch (_: Exception) {
            try { Instant.parse(iso) } catch (_: Exception) { null }
        }
    }

    private fun parsePrecision(p: String?): TPrecision = when (p?.lowercase()) {
        "exact" -> TPrecision.EXACT
        "day" -> TPrecision.DAY
        else -> TPrecision.APPROX
    }

    private fun minPrecision(a: TPrecision, b: TPrecision): TPrecision =
        // EXACT is "most precise"; pick the LESS precise of the two (conservative).
        if (a.ordinal >= b.ordinal) a else b

    private fun agree(a: Instant, b: Instant, prec: TPrecision, now: ZonedDateTime): Boolean = when (prec) {
        TPrecision.EXACT -> Math.abs(a.epochSecond - b.epochSecond) <= 60
        TPrecision.DAY -> sameLocalDate(a, b, now)
        TPrecision.APPROX -> Math.abs(ChronoUnit.DAYS.between(a, b)) <= 7
    }

    private fun sameLocalDate(a: Instant, b: Instant, now: ZonedDateTime): Boolean =
        a.atZone(now.zone).toLocalDate() == b.atZone(now.zone).toLocalDate()

    private fun closeEnough(a: Instant, b: Instant): Boolean =
        Math.abs(a.epochSecond - b.epochSecond) <= 60
}
