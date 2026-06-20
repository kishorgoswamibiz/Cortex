package com.cortex.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured JSON schema returned by DeepSeek for a single capture.
 * Mirrors §5 of the Technical Specification.
 */
@Serializable
data class ExtractionResult(
    val items: List<ExtractedItem> = emptyList()
)

@Serializable
data class ExtractedItem(
    val content: String,
    val kind: String, // fact | task | note | decision
    val domain: String, // work | personal | other
    val status: String? = null, // for tasks: open | done | null
    @SerialName("target_node") val targetNode: TargetNode,
    val links: List<ExtractedLink> = emptyList()
)

@Serializable
data class TargetNode(
    @SerialName("match_id") val matchId: String? = null,
    val new: NewNode? = null,
    val confidence: Double = 0.0
)

@Serializable
data class NewNode(
    val name: String,
    val type: String, // person | project | topic | hobby | idea
    val summary: String,
    val domain: String? = null
)

@Serializable
data class ExtractedLink(
    val entity: String,
    val relation: String, // colleague_on | member_of | related_to | mentioned_in
    @SerialName("match_id") val matchId: String? = null,
    val type: String? = null, // type to use if a new node must be created for the linked entity
    val domain: String? = null
)

/**
 * The minimal candidate descriptor sent to DeepSeek so it can either
 * choose an existing node by id or propose a new one.
 */
@Serializable
data class CandidateNode(
    val id: String,
    val name: String,
    val type: String,
    val summary: String,
    val domain: String? = null,
    // Known surface forms for this node (helps the model reuse it across paraphrases).
    val aliases: List<String> = emptyList(),
    // Retrieval signals (why this candidate surfaced) — guide the model to prefer
    // high-signal matches and avoid creating duplicates. Computed vs the capture.
    val lex: Float = 0f,        // best lexical (typo-tolerant) similarity 0..1
    val phon: Boolean = false,  // a phonetic (sound-alike) match
    val alias: Boolean = false, // matched a learned alias
    val vec: Float = 0f         // best semantic (embedding) similarity 0..1
)
