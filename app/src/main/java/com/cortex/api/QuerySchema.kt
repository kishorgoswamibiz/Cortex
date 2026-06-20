package com.cortex.api

import kotlinx.serialization.Serializable

/**
 * Schema for the intent + domain classifier call (Tech Spec §6 Step 1).
 * Cheap, fast Flash call that runs before retrieval.
 */
@Serializable
data class QueryClassification(
    val intent: String = "semantic",   // lookup | keyword | relational | semantic
    val domain: String = "mixed",      // work | personal | mixed
    val entities: List<String> = emptyList()
)

/**
 * Compact node descriptor sent into the answering prompt as context.
 */
@Serializable
data class AnswerContextNode(
    val id: String,
    val name: String,
    val type: String,
    val summary: String,
    val domain: String? = null,
    val items: List<AnswerContextItem> = emptyList(),
    val links: List<AnswerContextLink> = emptyList()
)

@Serializable
data class AnswerContextItem(
    val content: String,
    val kind: String,
    val domain: String,
    val status: String? = null
)

@Serializable
data class AnswerContextLink(
    @kotlinx.serialization.SerialName("target_id") val targetId: String,
    val target_name: String,
    val relation: String,
    val domain: String
)
