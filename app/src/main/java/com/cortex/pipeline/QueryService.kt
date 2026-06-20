package com.cortex.pipeline

import android.util.Log
import com.cortex.BuildConfig
import com.cortex.api.AnswerContextItem
import com.cortex.api.AnswerContextLink
import com.cortex.api.AnswerContextNode
import com.cortex.api.ChatCompletionRequest
import com.cortex.api.DeepSeekClient
import com.cortex.api.Message
import com.cortex.api.QueryClassification
import com.cortex.api.QueryPrompts
import com.cortex.api.ResponseFormat
import com.cortex.data.CortexDao
import com.cortex.data.NodeEntity
import kotlinx.serialization.json.Json

/**
 * Tech Spec §6 — read pipeline.
 *
 *   classify  ->  scoped retrieve  ->  context bundle  ->  answer
 *
 * Context separation is enforced at the SQL layer (`WHERE domain = ?`)
 * and by only traversing same-domain edges. The model never sees the
 * irrelevant slice, so it can't accidentally blur work and personal.
 */
class QueryService(
    private val dao: CortexDao,
    private val embeddings: EmbeddingService
) {

    data class Answer(
        val text: String,
        val sources: List<NodeEntity>,
        val classification: QueryClassification
    )

    private val authHeader: String?
        get() = BuildConfig.DEEPSEEK_API_KEY.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    suspend fun ask(question: String): Answer {
        val auth = authHeader ?: error("DEEPSEEK_API_KEY not configured")

        val classification = classify(question, auth)
        Log.i(TAG, "Classified: $classification")

        val seedNodes = resolveSeeds(classification, question)
        val expansion = if (seedNodes.isNotEmpty()) {
            dao.expandOneHop(seedNodes.map { it.id }, classification.domain)
        } else emptyList()

        val allNodes = (seedNodes + expansion).distinctBy { it.id }.take(15)
        val context = buildContextBundle(allNodes, classification.domain)

        val (text, sourceIds) = answer(question, context, auth)
        val sourceNodes = if (sourceIds.isNotEmpty()) {
            dao.getNodesByIds(sourceIds)
        } else allNodes.take(3)

        return Answer(text, sourceNodes, classification)
    }

    private suspend fun classify(question: String, auth: String): QueryClassification {
        val req = ChatCompletionRequest(
            model = "deepseek-chat",
            messages = listOf(
                Message("system", QueryPrompts.CLASSIFIER_SYSTEM),
                Message("user", QueryPrompts.classifierUser(question))
            ),
            responseFormat = ResponseFormat(type = "json_object"),
            maxTokens = 256
        )
        return try {
            val raw = DeepSeekClient.api.createCompletion(auth, req).choices.firstOrNull()?.message?.content
            if (raw.isNullOrBlank()) defaultClassification(question)
            else JSON.decodeFromString(QueryClassification.serializer(), raw)
        } catch (t: Throwable) {
            Log.w(TAG, "Classifier failed, using local heuristic: ${t.message}")
            defaultClassification(question)
        }
    }

    /** Cheap local fallback so the app degrades gracefully when offline. */
    private fun defaultClassification(question: String): QueryClassification {
        val lower = question.lowercase()
        val domain = when {
            listOf("birthday", "birthdays", "friend", "family", "personal", "social", "meet").any { it in lower } -> "personal"
            listOf("work", "project", "meeting", "deadline", "ticket", "task").any { it in lower } -> "work"
            else -> "mixed"
        }
        val intent = when {
            "how is" in lower || "connected" in lower -> "relational"
            "show me" in lower || "open " in lower -> "lookup"
            else -> "semantic"
        }
        // Best-effort entity grab: capitalized words.
        val ents = Regex("""\b[A-Z][\w'-]+\b""").findAll(question).map { it.value }.toList()
        return QueryClassification(intent = intent, domain = domain, entities = ents)
    }

    private suspend fun resolveSeeds(c: QueryClassification, question: String): List<NodeEntity> {
        val collected = linkedMapOf<String, NodeEntity>()
        val limitPerTerm = 4

        // 1) Named entities -> direct lookup
        for (e in c.entities) {
            dao.searchNodesScoped(e, c.domain, limitPerTerm).forEach { collected.putIfAbsent(it.id, it) }
        }
        // 2) Salient terms from the whole question
        for (term in CandidateRetrieval.mentions(question, maxTerms = 6)) {
            if (collected.size >= 8) break
            dao.searchNodesScoped(term, c.domain, limitPerTerm).forEach { collected.putIfAbsent(it.id, it) }
        }
        // 3) Vector-pull a few semantically-close nodes if we still don't have enough
        if (collected.size < 5) {
            val qVec = embeddings.embed(question)
            val pool = dao.getRecentNodes(50)
            val scored = pool.mapNotNull { node ->
                val emb = dao.getEmbedding("node", node.id)?.vector ?: return@mapNotNull null
                val score = Vectors.cosine(qVec, Vectors.fromBlob(emb))
                node to score
            }.sortedByDescending { it.second }
            for ((n, _) in scored.take(5 - collected.size)) collected.putIfAbsent(n.id, n)
        }
        // 4) Items-by-content also surface their parent node
        if (collected.size < 5) {
            for (term in CandidateRetrieval.mentions(question, maxTerms = 6)) {
                dao.searchItemsScoped(term, c.domain, 5).forEach { item ->
                    val parent = dao.getNodeById(item.nodeId) ?: return@forEach
                    collected.putIfAbsent(parent.id, parent)
                }
                if (collected.size >= 8) break
            }
        }
        return collected.values.toList()
    }

    private suspend fun buildContextBundle(nodes: List<NodeEntity>, domain: String): List<AnswerContextNode> {
        return nodes.map { node ->
            val items = dao.getItemsForNode(node.id, domain).take(8)
            val edges = dao.getEdgesForNode(node.id, domain)
            val linkedIds = edges.map { if (it.sourceNodeId == node.id) it.targetNodeId else it.sourceNodeId }.distinct()
            val linkedNodes = dao.getNodesByIds(linkedIds).associateBy { it.id }
            val links = edges.mapNotNull { e ->
                val otherId = if (e.sourceNodeId == node.id) e.targetNodeId else e.sourceNodeId
                val other = linkedNodes[otherId] ?: return@mapNotNull null
                AnswerContextLink(targetId = other.id, target_name = other.name, relation = e.relationType, domain = e.domain)
            }
            AnswerContextNode(
                id = node.id,
                name = node.name,
                type = node.type,
                summary = node.summary,
                domain = node.domain,
                items = items.map { AnswerContextItem(it.content, it.kind, it.domain, it.status) },
                links = links
            )
        }
    }

    private suspend fun answer(
        question: String,
        context: List<AnswerContextNode>,
        auth: String
    ): Pair<String, List<String>> {
        if (context.isEmpty()) {
            return "I don't have anything on that yet." to emptyList()
        }
        val req = ChatCompletionRequest(
            model = "deepseek-chat",
            messages = listOf(
                Message("system", QueryPrompts.ANSWER_SYSTEM),
                Message("user", QueryPrompts.answerUser(question, context))
            ),
            maxTokens = 1024
        )
        val raw = DeepSeekClient.api.createCompletion(auth, req).choices.firstOrNull()?.message?.content
            ?: return "Something went wrong fetching an answer." to emptyList()

        val sourceLine = Regex("""(?m)^\s*SOURCES?:\s*(.+)$""").find(raw)
        val sources = sourceLine?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val text = if (sourceLine != null) raw.removeRange(sourceLine.range).trim() else raw.trim()
        return text to sources
    }

    companion object {
        private const val TAG = "QueryService"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    }
}
