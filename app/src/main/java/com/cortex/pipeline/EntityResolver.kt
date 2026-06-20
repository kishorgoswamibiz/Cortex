package com.cortex.pipeline

import com.cortex.api.CandidateNode
import com.cortex.api.NewNode
import com.cortex.data.CortexDao

/**
 * Resolves a proposed *new* node against the retrieved candidates using a blended
 * score (lexical + phonetic + semantic) gated by confidence bands.
 *
 *   STRONG  -> attach to existing node (only when real embeddings back the match)
 *   MEDIUM  -> create, but flag for the confirmation inbox (never silent-merge)
 *   LOW     -> create new
 *
 * Auto-merge is deliberately gated on having real MiniLM vectors AND on the name
 * not being ambiguous (two candidates with the same name) — both guard against
 * the worst failure mode: silently fusing two distinct entities.
 */
class EntityResolver(
    private val dao: CortexDao,
    private val embeddings: EmbeddingService
) {

    enum class Band { STRONG, MEDIUM, LOW }

    data class Resolution(
        val matchedId: String?,        // non-null only on STRONG (safe to auto-attach)
        val score: Float,
        val band: Band,
        val needsConfirmation: Boolean
    )

    private val fallback = "fallback-hash-v1"

    suspend fun resolve(
        newNode: NewNode,
        candidates: List<CandidateNode>,
        strongThreshold: Float = 0.86f,
        mediumThreshold: Float = 0.62f
    ): Resolution {
        if (candidates.isEmpty()) return Resolution(null, 0f, Band.LOW, false)

        val targetNorm = NameNormalizer.normalize(newNode.name)
        val targetKey = Phonetic.key(newNode.name)
        val realEmb = embeddings.modelVersion != fallback
        val targetVec = if (realEmb) embeddings.embed("${newNode.name}. ${newNode.summary}") else null

        var best: CandidateNode? = null
        var bestScore = -1f
        for (c in candidates) {
            val tp = typePenalty(newNode.type, c.type) ?: continue   // null => incompatible, skip
            val cNorm = NameNormalizer.normalize(c.name)
            val nameSim = if (cNorm == targetNorm) 1f else FuzzyMatch.lexSim(targetNorm, cNorm)
            val phon = if (targetKey.isNotBlank() && targetKey == Phonetic.key(c.name)) 1f else 0f
            val vecSim = if (targetVec != null) {
                val blob = dao.getEmbedding("node", c.id)?.vector
                val cVec = if (blob != null) Vectors.fromBlob(blob) else embeddings.embed("${c.name}. ${c.summary}")
                Vectors.cosine(targetVec, cVec)
            } else 0f

            val base = if (realEmb) 0.45f * nameSim + 0.20f * phon + 0.35f * vecSim
                       else 0.70f * nameSim + 0.30f * phon         // renormalized without vector
            val aliasBoost = if (c.alias) 0.10f else 0f
            val score = (tp * base + aliasBoost).coerceIn(0f, 1f)
            if (score > bestScore) { bestScore = score; best = c }
        }

        if (best == null) return Resolution(null, 0f, Band.LOW, false)

        // Ambiguity guard: ≥2 candidates share the target's normalized name.
        val ambiguous = candidates.count { NameNormalizer.normalize(it.name) == targetNorm } >= 2

        return when {
            bestScore >= strongThreshold && realEmb && !ambiguous ->
                Resolution(best.id, bestScore, Band.STRONG, false)
            bestScore >= mediumThreshold ->
                // includes strong-but-fallback and strong-but-ambiguous: create + confirm
                Resolution(null, bestScore, Band.MEDIUM, true)
            else ->
                Resolution(null, bestScore.coerceAtLeast(0f), Band.LOW, false)
        }
    }

    /** Type as a strong prior, not a hard filter. null = incompatible (never merge). */
    private fun typePenalty(a: String, b: String): Float? {
        if (a == b) return 1f
        if (a == "person" || b == "person") return null  // a person must never merge cross-type
        val pair = setOf(a, b)
        val compatible = listOf(
            setOf("topic", "project"), setOf("idea", "project"),
            setOf("topic", "idea"), setOf("hobby", "topic")
        )
        return if (pair in compatible) 0.85f else 0.70f
    }
}
