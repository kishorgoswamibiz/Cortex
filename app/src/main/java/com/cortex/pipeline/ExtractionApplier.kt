package com.cortex.pipeline

import com.cortex.api.CandidateNode
import com.cortex.api.ExtractionResult
import com.cortex.data.CortexDao
import com.cortex.data.EdgeEntity
import com.cortex.data.EmbeddingEntity
import com.cortex.data.ItemEntity
import com.cortex.data.NodeEntity
import com.cortex.data.ReminderEntity
import java.util.UUID

/**
 * Converts the LLM ExtractionResult into Room entities and writes them under one
 * transaction, plus: attaches the reconciled temporal anchor, creates reminders,
 * stamps phonetic keys on new nodes, and learns aliases (post-commit). Tech plan
 * Phase 3.
 */
class ExtractionApplier(
    private val dao: CortexDao,
    private val resolver: EntityResolver,
    private val embeddings: EmbeddingService
) {

    data class ApplyOutcome(
        val itemsWritten: Int,
        val nodesCreated: Int,
        val edgesCreated: Int,
        val confirmationsNeeded: Int,
        val reminders: List<ReminderEntity>
    )

    private data class AliasLearning(val nodeId: String, val surface: String, val source: String)

    private val aliasStop = setOf("thing", "things", "stuff", "it", "this", "that", "them", "those", "someone", "something")

    suspend fun apply(
        captureId: String,
        result: ExtractionResult,
        candidates: List<CandidateNode>,
        normalized: NormalizeService.Normalized? = null,
        zoneId: String = "UTC"
    ): ApplyOutcome {
        val now = System.currentTimeMillis()

        val nodesOut = mutableListOf<NodeEntity>()
        val edgesOut = mutableListOf<EdgeEntity>()
        val itemsOut = mutableListOf<ItemEntity>()
        val embeddingsOut = mutableListOf<EmbeddingEntity>()
        val aliasLearnings = mutableListOf<AliasLearning>()
        var confirmations = 0

        val nameIndex = HashMap<String, String>()
        candidates.forEach { nameIndex[NameNormalizer.normalize(it.name)] = it.id }

        for (extracted in result.items) {
            val target = extracted.targetNode

            val targetNodeId: String = when {
                target.matchId != null && candidates.any { it.id == target.matchId } -> target.matchId
                target.new != null -> {
                    val resolution = resolver.resolve(target.new, candidates)
                    if (resolution.matchedId != null) {
                        aliasLearnings.add(AliasLearning(resolution.matchedId, target.new.name, "observed"))
                        resolution.matchedId
                    } else {
                        if (resolution.needsConfirmation) confirmations++
                        newNode(target.new.name, target.new.type, target.new.summary, target.new.domain, now, nodesOut, embeddingsOut, aliasLearnings)
                    }
                }
                else -> inboxNodeId(now, nodesOut, embeddingsOut)
            }

            for (link in extracted.links) {
                val linkedId = resolveLinkedEntity(link.entity, link.matchId, link.type, candidates, nameIndex, now, nodesOut, embeddingsOut, aliasLearnings)
                edgesOut.add(
                    EdgeEntity(
                        id = UUID.randomUUID().toString(),
                        sourceNodeId = targetNodeId,   // for member_of/colleague_on this is the person (prompt rule)
                        targetNodeId = linkedId,
                        relationType = link.relation,
                        domain = link.domain ?: extracted.domain,
                        createdAt = now
                    )
                )
            }

            // relatedNodeId = the *primary* related node by relation priority (edges hold the full set).
            val primaryRelated = extracted.links
                .sortedBy { relationPriority(it.relation) }
                .firstOrNull()
                ?.let { nameIndex[NameNormalizer.normalize(it.entity)] }

            val item = ItemEntity(
                id = UUID.randomUUID().toString(),
                nodeId = targetNodeId,
                content = extracted.content,
                kind = extracted.kind,
                domain = extracted.domain,
                status = extracted.status ?: if (extracted.kind == "task") "open" else null,
                relatedNodeId = primaryRelated,
                sourceCaptureId = captureId,
                createdAt = now
            )
            itemsOut.add(item)
            embeddingsOut.add(buildEmbedding("item", item.id, item.content))
        }

        // Attach the reconciled temporal anchor to the most actionable item, and
        // create a reminder when there is reminder intent or a dated task.
        val reminders = mutableListOf<ReminderEntity>()
        val anchor = normalized?.primary
        if (anchor != null && itemsOut.isNotEmpty()) {
            val idx = itemsOut.indexOfFirst { it.kind == "task" }.let { if (it >= 0) it else 0 }
            val makeReminder = normalized.hasReminderIntent || itemsOut[idx].kind == "task"
            val triggerMs = anchor.instant.toEpochMilli()
            itemsOut[idx] = itemsOut[idx].copy(
                dueAt = triggerMs,
                remindAt = if (makeReminder) triggerMs else null,
                temporalPrecision = anchor.precision.name.lowercase(),
                temporalPhrase = anchor.phrase
            )
            if (makeReminder && triggerMs > now) {
                val item = itemsOut[idx]
                reminders.add(
                    ReminderEntity(
                        id = UUID.randomUUID().toString(),
                        title = normalized.reminderTitle?.takeIf { it.isNotBlank() } ?: item.content,
                        body = null,
                        triggerAt = triggerMs,
                        status = "scheduled",
                        relatedNodeId = item.nodeId,
                        relatedItemId = item.id,
                        sourceCaptureId = captureId,
                        timeZone = zoneId,
                        createdAt = now
                    )
                )
            }
        }

        dao.applyExtraction(nodesOut, edgesOut, itemsOut, embeddingsOut, captureId, now)
        if (reminders.isNotEmpty()) dao.insertReminders(reminders)

        // Alias learning runs AFTER the transaction so new node rows exist (FK) and
        // collision checks see the final graph.
        for (a in aliasLearnings) writeAlias(a.nodeId, a.surface, a.source, now)

        return ApplyOutcome(
            itemsWritten = itemsOut.size,
            nodesCreated = nodesOut.size,
            edgesCreated = edgesOut.size,
            confirmationsNeeded = confirmations,
            reminders = reminders
        )
    }

    private fun relationPriority(relation: String): Int = when (relation) {
        "member_of", "colleague_on" -> 0
        "related_to" -> 1
        "mentioned_in" -> 2
        else -> 1
    }

    private fun newNode(
        name: String,
        type: String,
        summary: String,
        domain: String?,
        now: Long,
        nodesOut: MutableList<NodeEntity>,
        embeddingsOut: MutableList<EmbeddingEntity>,
        aliasLearnings: MutableList<AliasLearning>
    ): String {
        val id = UUID.randomUUID().toString()
        nodesOut.add(
            NodeEntity(
                id = id,
                name = name,
                summary = summary,
                type = type,
                domain = domain,
                parentId = null,
                phoneticKey = Phonetic.key(name),
                createdAt = now,
                updatedAt = now
            )
        )
        embeddingsOut.add(buildEmbedding("node", id, "$name. $summary"))
        aliasLearnings.add(AliasLearning(id, name, "canonical"))
        return id
    }

    private fun resolveLinkedEntity(
        entity: String,
        matchId: String?,
        type: String?,
        candidates: List<CandidateNode>,
        nameIndex: MutableMap<String, String>,
        now: Long,
        nodesOut: MutableList<NodeEntity>,
        embeddingsOut: MutableList<EmbeddingEntity>,
        aliasLearnings: MutableList<AliasLearning>
    ): String {
        if (matchId != null && candidates.any { it.id == matchId }) {
            aliasLearnings.add(AliasLearning(matchId, entity, "observed"))
            return matchId
        }
        val norm = NameNormalizer.normalize(entity)
        nameIndex[norm]?.let {
            aliasLearnings.add(AliasLearning(it, entity, "observed"))
            return it
        }
        candidates.firstOrNull { NameNormalizer.normalize(it.name) == norm }?.let {
            nameIndex[norm] = it.id
            aliasLearnings.add(AliasLearning(it.id, entity, "observed"))
            return it.id
        }
        val newId = newNode(entity, type ?: "person", "", null, now, nodesOut, embeddingsOut, aliasLearnings)
        nameIndex[norm] = newId
        return newId
    }

    private suspend fun writeAlias(nodeId: String, surface: String, source: String, now: Long) {
        val norm = NameNormalizer.coreNorm(surface)
        if (norm.isBlank() || norm.length < 2 || norm in aliasStop || norm.all { it.isDigit() }) return
        // Collision: this alias already belongs to a different node -> ambiguous, don't learn.
        val owners = dao.aliasOwners(norm)
        if (owners.isNotEmpty() && nodeId !in owners) return
        dao.upsertAliasBumpCount(nodeId, norm, surface.trim(), Phonetic.key(surface), source, now)
    }

    private var cachedInboxId: String? = null
    private suspend fun inboxNodeId(
        now: Long,
        nodesOut: MutableList<NodeEntity>,
        embeddingsOut: MutableList<EmbeddingEntity>
    ): String {
        cachedInboxId?.let { return it }
        val existing = dao.searchNodesLike("Inbox", 1).firstOrNull { it.name == "Inbox" && it.type == "topic" }
        if (existing != null) { cachedInboxId = existing.id; return existing.id }
        val id = UUID.randomUUID().toString()
        nodesOut.add(
            NodeEntity(
                id = id, name = "Inbox",
                summary = "Catch-all for items the extractor could not file with confidence.",
                type = "topic", domain = null, parentId = null,
                phoneticKey = Phonetic.key("Inbox"), createdAt = now, updatedAt = now
            )
        )
        embeddingsOut.add(buildEmbedding("node", id, "Inbox."))
        cachedInboxId = id
        return id
    }

    private fun buildEmbedding(ownerType: String, ownerId: String, text: String): EmbeddingEntity {
        val vec = embeddings.embed(text)
        return EmbeddingEntity(ownerType, ownerId, Vectors.toBlob(vec), embeddings.modelVersion)
    }
}
