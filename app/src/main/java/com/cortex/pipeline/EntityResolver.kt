package com.cortex.pipeline

import com.cortex.api.CandidateNode
import com.cortex.api.NewNode
import com.cortex.data.CortexDao

/**
 * Tech Spec §5 Step 3 — for each proposed *new* node, re-check against the
 * candidate set using normalized-name match and embedding cosine similarity.
 *
 *   strong match  -> attach to existing node (return existing id)
 *   no match      -> caller creates a new node
 *   medium match  -> tagged needsConfirmation (UI confirmation in a later phase)
 */
class EntityResolver(
    private val dao: CortexDao,
    private val embeddings: EmbeddingService
) {

    data class Resolution(
        val matchedId: String?,        // non-null if a strong match was found
        val similarity: Float,         // 0..1
        val needsConfirmation: Boolean
    )

    suspend fun resolve(
        newNode: NewNode,
        candidates: List<CandidateNode>,
        strongThreshold: Float = 0.85f,
        mediumThreshold: Float = 0.65f
    ): Resolution {
        if (candidates.isEmpty()) return Resolution(null, 0f, needsConfirmation = false)

        val normTarget = normalize(newNode.name)

        // 1) exact normalized-name match against same-type candidates is a strong signal.
        candidates
            .firstOrNull { normalize(it.name) == normTarget && it.type == newNode.type }
            ?.let { return Resolution(it.id, 1f, needsConfirmation = false) }

        // 2) embedding similarity over name + summary.
        val targetVec = embeddings.embed("${newNode.name}. ${newNode.summary}")
        var bestId: String? = null
        var bestScore = -1f
        for (c in candidates) {
            // Only compare same-type candidates; a Project shouldn't merge into a Person.
            if (c.type != newNode.type) continue
            val storedBlob = dao.getEmbedding("node", c.id)?.vector
            val cVec = if (storedBlob != null) {
                Vectors.fromBlob(storedBlob)
            } else {
                embeddings.embed("${c.name}. ${c.summary}")
            }
            val score = Vectors.cosine(targetVec, cVec)
            if (score > bestScore) { bestScore = score; bestId = c.id }
        }

        return when {
            bestId != null && bestScore >= strongThreshold ->
                Resolution(bestId, bestScore, needsConfirmation = false)
            bestId != null && bestScore >= mediumThreshold ->
                Resolution(bestId, bestScore, needsConfirmation = true)
            else ->
                Resolution(null, bestScore.coerceAtLeast(0f), needsConfirmation = false)
        }
    }

    private fun normalize(s: String): String =
        s.trim().lowercase()
            .replace(Regex("^(mr|mrs|ms|dr)\\.?\\s+"), "")
            .replace(Regex("[^a-z0-9 ]+"), "")
            .replace(Regex("\\s+"), " ")
}
