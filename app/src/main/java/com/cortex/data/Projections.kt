package com.cortex.data

/**
 * Lightweight row projection used by hybrid candidate retrieval so we can score
 * fuzzy/phonetic similarity in Kotlin without loading whole NodeEntity rows.
 */
data class NodeNameKey(
    val id: String,
    val name: String,
    val type: String,
    val phoneticKey: String?
)
