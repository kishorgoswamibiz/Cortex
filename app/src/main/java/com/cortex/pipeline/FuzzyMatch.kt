package com.cortex.pipeline

import kotlin.math.max

/**
 * Typo-tolerant lexical similarity (trigram Jaccard + normalized Levenshtein)
 * and a lightweight phonetic key. These are deterministic, offline, and cheap —
 * they catch "Rmaesh"≈"Ramesh" and "Catherine"≈"Katherine" without embeddings.
 */
object FuzzyMatch {

    fun trigrams(s: String): Set<String> {
        val padded = "  ${s.trim()} "
        if (padded.length < 3) return setOf(padded)
        return (0..padded.length - 3).map { padded.substring(it, it + 3) }.toSet()
    }

    fun jaccard(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val ta = trigrams(a); val tb = trigrams(b)
        val inter = ta.count { it in tb }.toFloat()
        val union = (ta.size + tb.size - inter)
        return if (union <= 0f) 0f else inter / union
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    fun levSim(a: String, b: String): Float {
        val m = max(a.length, b.length)
        if (m == 0) return 1f
        return 1f - levenshtein(a, b).toFloat() / m
    }

    /** Blended lexical similarity in [0,1]. */
    fun lexSim(a: String, b: String): Float = 0.5f * jaccard(a, b) + 0.5f * levSim(a, b)
}

/**
 * Compact phonetic encoder (Metaphone-style): maps similar-sounding consonants to
 * shared codes and drops non-leading vowels, so sound-alike spellings collide.
 * Not full Double-Metaphone, but deterministic and effective for personal names.
 */
object Phonetic {
    fun key(input: String): String {
        val u = NameNormalizer.normalize(input).replace(" ", "")
        if (u.isEmpty()) return ""
        val sb = StringBuilder()
        for ((i, c) in u.withIndex()) {
            val mapped = when (c) {
                'a', 'e', 'i', 'o', 'u' -> if (i == 0) c.uppercaseChar().toString() else ""
                'b', 'p' -> "P"
                'v', 'f' -> "F"
                'c', 'k', 'q', 'g' -> "K"
                'j' -> "J"
                's', 'z', 'x' -> "S"
                'd', 't' -> "T"
                'm', 'n' -> "N"
                'l' -> "L"
                'r' -> "R"
                'w', 'h', 'y' -> ""
                else -> c.uppercaseChar().toString()
            }
            if (mapped.isNotEmpty() && (sb.isEmpty() || sb.last() != mapped[0])) sb.append(mapped)
        }
        return sb.toString()
    }
}
