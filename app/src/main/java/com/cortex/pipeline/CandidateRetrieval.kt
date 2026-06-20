package com.cortex.pipeline

import com.cortex.api.CandidateNode
import com.cortex.data.CortexDao
import com.cortex.data.NodeEntity

/**
 * Tech Spec §5 Step 1 — pull a small set of plausibly-matching existing nodes
 * BEFORE the extraction LLM call. Keeps the model from having to see the
 * whole graph, and prevents duplicate creation of entities like "Cam".
 */
object CandidateRetrieval {

    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be",
        "been", "being", "have", "has", "had", "do", "does", "did", "will",
        "would", "should", "could", "may", "might", "must", "can", "of", "to",
        "in", "on", "at", "for", "with", "by", "from", "about", "as", "into",
        "than", "this", "that", "these", "those", "i", "you", "he", "she", "we",
        "they", "it", "me", "my", "your", "our", "their", "his", "her", "its",
        "so", "not", "no", "yes", "if", "then", "just", "also"
    )

    /** Extract candidate terms: capitalized phrases + significant single words. */
    fun salientTerms(text: String, maxTerms: Int = 12): List<String> {
        val out = linkedSetOf<String>()

        // 1) Capitalized multi-word phrases ("Project Phoenix", "Cam Wilson")
        val capPhrase = Regex("""\b([A-Z][\w'-]+(?:\s+[A-Z][\w'-]+)*)\b""")
        capPhrase.findAll(text).forEach { out.add(it.value.trim()) }

        // 2) Significant lowercase words (length>=4, not stopwords)
        text.split(Regex("[^A-Za-z0-9']+"))
            .map { it.trim() }
            .filter { it.length >= 4 }
            .filter { it.lowercase() !in STOPWORDS }
            .filter { it.first().isLowerCase() }
            .forEach { out.add(it) }

        return out.take(maxTerms)
    }

    suspend fun retrieve(
        dao: CortexDao,
        captureText: String,
        perTermLimit: Int = 5,
        totalLimit: Int = 20
    ): List<CandidateNode> {
        val terms = salientTerms(captureText)
        val collected = linkedMapOf<String, NodeEntity>()

        for (term in terms) {
            val hits = dao.searchNodesLike(term, perTermLimit)
            for (n in hits) {
                if (collected.size >= totalLimit) break
                collected.putIfAbsent(n.id, n)
            }
            if (collected.size >= totalLimit) break
        }

        // Always include a few recently-touched nodes as a fallback context.
        if (collected.size < totalLimit) {
            val recent = dao.getRecentNodes(totalLimit - collected.size)
            for (n in recent) {
                collected.putIfAbsent(n.id, n)
                if (collected.size >= totalLimit) break
            }
        }

        return collected.values.map {
            CandidateNode(
                id = it.id,
                name = it.name,
                type = it.type,
                summary = it.summary,
                domain = it.domain
            )
        }
    }
}
