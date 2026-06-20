package com.cortex.pipeline

import com.cortex.api.CandidateNode
import com.cortex.api.ExtractedItem
import com.cortex.api.ExtractionResult
import com.cortex.data.CortexDao
import com.cortex.data.EdgeEntity
import com.cortex.data.EmbeddingEntity
import com.cortex.data.ItemEntity
import com.cortex.data.NodeEntity
import java.util.UUID

/**
 * Tech Spec §5 Step 4 — convert the LLM ExtractionResult into the concrete
 * Room entities and write them under a single transaction along with
 * on-device embeddings for new/changed nodes and items.
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
        val confirmationsNeeded: Int
    )

    suspend fun apply(
        captureId: String,
        result: ExtractionResult,
        candidates: List<CandidateNode>
    ): ApplyOutcome {
        val now = System.currentTimeMillis()

        val nodesOut = mutableListOf<NodeEntity>()
        val edgesOut = mutableListOf<EdgeEntity>()
        val itemsOut = mutableListOf<ItemEntity>()
        val embeddingsOut = mutableListOf<EmbeddingEntity>()
        var confirmations = 0

        // entity-name (lowercased) -> resolved node id, so multiple items referencing
        // "Cam" within one capture share the same node.
        val nameIndex = HashMap<String, String>()
        candidates.forEach { nameIndex[it.name.lowercase()] = it.id }

        for (extracted in result.items) {
            val target = extracted.targetNode

            val targetNodeId: String = when {
                target.matchId != null && candidates.any { it.id == target.matchId } -> target.matchId
                target.new != null -> {
                    val resolution = resolver.resolve(target.new, candidates)
                    if (resolution.matchedId != null && !resolution.needsConfirmation) {
                        resolution.matchedId
                    } else {
                        if (resolution.needsConfirmation) confirmations++
                        val newId = newNode(
                            name = target.new.name,
                            type = target.new.type,
                            summary = target.new.summary,
                            domain = target.new.domain,
                            now = now,
                            nodesOut = nodesOut,
                            embeddingsOut = embeddingsOut
                        )
                        nameIndex[target.new.name.lowercase()] = newId
                        newId
                    }
                }
                else -> {
                    // Fall back: file under a generic "Inbox" node so nothing is lost.
                    inboxNodeId(now, nodesOut, embeddingsOut)
                }
            }

            // Lateral links extracted with the item.
            for (link in extracted.links) {
                val linkedId = resolveLinkedEntity(link.entity, link.matchId, link.type, candidates, nameIndex, now, nodesOut, embeddingsOut)
                edgesOut.add(
                    EdgeEntity(
                        id = UUID.randomUUID().toString(),
                        sourceNodeId = targetNodeId,
                        targetNodeId = linkedId,
                        relationType = link.relation,
                        domain = link.domain ?: extracted.domain,
                        createdAt = now
                    )
                )
            }

            val item = ItemEntity(
                id = UUID.randomUUID().toString(),
                nodeId = targetNodeId,
                content = extracted.content,
                kind = extracted.kind,
                domain = extracted.domain,
                status = extracted.status ?: if (extracted.kind == "task") "open" else null,
                relatedNodeId = extracted.links.firstOrNull()?.let { nameIndex[it.entity.lowercase()] },
                sourceCaptureId = captureId,
                createdAt = now
            )
            itemsOut.add(item)
            embeddingsOut.add(buildEmbedding("item", item.id, item.content))
        }

        dao.applyExtraction(nodesOut, edgesOut, itemsOut, embeddingsOut, captureId, now)

        return ApplyOutcome(
            itemsWritten = itemsOut.size,
            nodesCreated = nodesOut.size,
            edgesCreated = edgesOut.size,
            confirmationsNeeded = confirmations
        )
    }

    private fun newNode(
        name: String,
        type: String,
        summary: String,
        domain: String?,
        now: Long,
        nodesOut: MutableList<NodeEntity>,
        embeddingsOut: MutableList<EmbeddingEntity>
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
                createdAt = now,
                updatedAt = now
            )
        )
        embeddingsOut.add(buildEmbedding("node", id, "$name. $summary"))
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
        embeddingsOut: MutableList<EmbeddingEntity>
    ): String {
        if (matchId != null && candidates.any { it.id == matchId }) return matchId
        nameIndex[entity.lowercase()]?.let { return it }
        // Look up by exact normalized name in candidates as a last sanity pass.
        candidates.firstOrNull { it.name.equals(entity, ignoreCase = true) }?.let { return it.id }
        val newId = newNode(
            name = entity,
            type = type ?: "person",
            summary = "",
            domain = null,
            now = now,
            nodesOut = nodesOut,
            embeddingsOut = embeddingsOut
        )
        nameIndex[entity.lowercase()] = newId
        return newId
    }

    private var cachedInboxId: String? = null
    private suspend fun inboxNodeId(
        now: Long,
        nodesOut: MutableList<NodeEntity>,
        embeddingsOut: MutableList<EmbeddingEntity>
    ): String {
        cachedInboxId?.let { return it }
        // Look for an existing Inbox node first.
        val existing = dao.searchNodesLike("Inbox", 1).firstOrNull { it.name == "Inbox" && it.type == "topic" }
        if (existing != null) {
            cachedInboxId = existing.id
            return existing.id
        }
        val id = newNode(
            name = "Inbox",
            type = "topic",
            summary = "Catch-all for items the extractor could not file with confidence.",
            domain = null,
            now = now,
            nodesOut = nodesOut,
            embeddingsOut = embeddingsOut
        )
        cachedInboxId = id
        return id
    }

    private fun buildEmbedding(ownerType: String, ownerId: String, text: String): EmbeddingEntity {
        val vec = embeddings.embed(text)
        return EmbeddingEntity(
            ownerType = ownerType,
            ownerId = ownerId,
            vector = Vectors.toBlob(vec),
            modelVersion = embeddings.modelVersion
        )
    }
}
