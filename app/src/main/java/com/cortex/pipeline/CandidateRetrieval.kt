package com.cortex.pipeline

import com.cortex.api.CandidateNode
import com.cortex.data.CortexDao
import com.cortex.data.NodeEntity

/**
 * Tech plan §New algorithms — hybrid multi-recall + Reciprocal Rank Fusion.
 *
 * Replaces the old LIKE-only retrieval. We pull candidates via five independent
 * signals (lexical LIKE, trigram-fuzzy, phonetic, learned alias, embedding KNN),
 * fuse their *ranks* with RRF (robust to incomparable score scales), and surface
 * the top-N to the extraction LLM annotated with WHY each one matched. We no
 * longer pad with recent nodes — surfacing unrelated nodes caused mis-tags.
 */
object CandidateRetrieval {

    private const val RRF_K = 60f
    private val WEIGHTS = mapOf(
        "alias" to 1.5f, "vector" to 1.2f, "like" to 1.0f, "trigram" to 1.0f, "phonetic" to 0.8f
    )
    private const val FALLBACK_MODEL = "fallback-hash-v1"

    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be",
        "been", "being", "have", "has", "had", "do", "does", "did", "will",
        "would", "should", "could", "may", "might", "must", "can", "of", "to",
        "in", "on", "at", "for", "with", "by", "from", "about", "as", "into",
        "than", "this", "that", "these", "those", "i", "you", "he", "she", "we",
        "they", "it", "me", "my", "your", "our", "their", "his", "her", "its",
        "so", "not", "no", "yes", "if", "then", "just", "also", "remind", "remember"
    )

    /** Candidate mentions (likely entity references) from the (cleaned) capture. */
    fun mentions(text: String, maxTerms: Int = 12): List<String> {
        val out = linkedSetOf<String>()
        Regex("""\b([A-Z][\w'-]+(?:\s+[A-Z][\w'-]+)*)\b""").findAll(text).forEach { out.add(it.value.trim()) }
        text.split(Regex("[^A-Za-z0-9']+"))
            .map { it.trim() }
            .filter { it.length >= 4 && it.lowercase() !in STOPWORDS && it.first().isLowerCase() }
            .forEach { out.add(it) }
        return out.take(maxTerms)
    }

    suspend fun retrieve(
        dao: CortexDao,
        embeddings: EmbeddingService,
        captureText: String,
        topN: Int = 10
    ): List<CandidateNode> {
        val mentions = mentions(captureText)
        if (mentions.isEmpty()) return emptyList()

        val nodeById = HashMap<String, NodeEntity>()
        val signals = LinkedHashMap<String, List<String>>()

        // All node name/type/phonetic keys (cheap projection; graph is small).
        val keys = dao.getNodeNameKeys()
        val mentionNorms = mentions.map { NameNormalizer.normalize(it) }

        // 1) lexical LIKE
        val likeHits = linkedSetOf<String>()
        for (m in mentions) dao.searchNodesLike(m, 5).forEach { likeHits.add(it.id); nodeById[it.id] = it }
        signals["like"] = likeHits.toList()

        // 2) trigram fuzzy (typo-tolerant)
        val lexSimById = HashMap<String, Float>()
        val trigramRanked = keys.mapNotNull { k ->
            val kn = NameNormalizer.normalize(k.name)
            val best = mentionNorms.maxOfOrNull { FuzzyMatch.lexSim(it, kn) } ?: 0f
            if (best >= 0.45f) { lexSimById[k.id] = best; k.id to best } else null
        }.sortedByDescending { it.second }.map { it.first }
        signals["trigram"] = trigramRanked

        // 3) phonetic (sound-alike)
        val mentionKeys = mentions.map { Phonetic.key(it) }.filter { it.isNotBlank() }.toSet()
        val phoneticIds = keys.filter { it.phoneticKey != null && it.phoneticKey in mentionKeys }.map { it.id }
        signals["phonetic"] = phoneticIds
        val phoneticSet = phoneticIds.toSet()

        // 4) learned aliases
        val aliasIds = linkedSetOf<String>()
        for (m in mentions) {
            dao.searchNodesByAlias(NameNormalizer.coreNorm(m), 5).forEach { aliasIds.add(it.id); nodeById[it.id] = it }
        }
        signals["alias"] = aliasIds.toList()
        val aliasSet = aliasIds.toSet()

        // 5) embedding KNN (only when real MiniLM vectors exist)
        val vectorSimById = HashMap<String, Float>()
        if (embeddings.modelVersion != FALLBACK_MODEL) {
            val nodeEmbs = dao.getEmbeddingsByOwnerType("node").filter { it.modelVersion == embeddings.modelVersion }
            if (nodeEmbs.isNotEmpty()) {
                val mentionVecs = mentions.map { embeddings.embed(it) }
                for (e in nodeEmbs) {
                    val v = Vectors.fromBlob(e.vector)
                    val best = mentionVecs.maxOfOrNull { Vectors.cosine(it, v) } ?: 0f
                    if (best > 0f) vectorSimById[e.ownerId] = best
                }
            }
        }
        signals["vector"] = vectorSimById.entries.sortedByDescending { it.value }.take(20).map { it.key }

        // Reciprocal Rank Fusion
        val rrf = HashMap<String, Float>()
        for ((name, list) in signals) {
            val w = WEIGHTS[name] ?: 1f
            list.forEachIndexed { i, id -> rrf[id] = (rrf[id] ?: 0f) + w / (RRF_K + (i + 1)) }
        }
        val top = rrf.entries.sortedByDescending { it.value }.take(topN).map { it.key }

        val missing = top.filter { it !in nodeById }
        if (missing.isNotEmpty()) dao.getNodesByIds(missing).forEach { nodeById[it.id] = it }

        return top.mapNotNull { id ->
            val n = nodeById[id] ?: return@mapNotNull null
            CandidateNode(
                id = n.id,
                name = n.name,
                type = n.type,
                summary = n.summary,
                domain = n.domain,
                aliases = dao.getAliasSurfaces(id, 5),
                lex = lexSimById[id] ?: 0f,
                phon = id in phoneticSet,
                alias = id in aliasSet,
                vec = vectorSimById[id] ?: 0f
            )
        }
    }
}
