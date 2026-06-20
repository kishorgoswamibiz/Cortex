package com.cortex.pipeline

/**
 * Single shared name normalizer used by candidate retrieval, entity resolution,
 * and alias storage — so retrieval keys and stored keys never drift.
 */
object NameNormalizer {
    private val TITLES = Regex("^(mr|mrs|ms|dr|prof|sir)\\.?\\s+")
    private val INTRO = Regex(
        "^(my|our|the)\\s+(close\\s+|good\\s+|old\\s+)?" +
            "(friend|colleague|boss|manager|cousin|brother|sister|mom|dad|mother|father|" +
            "wife|husband|partner|teammate|buddy|mate|client|neighbou?r)\\s+"
    )

    /** Canonical lowercase key: drop titles, punctuation, collapse whitespace. */
    fun normalize(s: String): String =
        s.trim().lowercase()
            .replace(TITLES, "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Strip a relational intro ("my friend Ramesh" -> "ramesh") then normalize. */
    fun coreNorm(surface: String): String {
        val lower = surface.trim().lowercase()
        val stripped = INTRO.replace(lower, "")
        return normalize(stripped)
    }
}
