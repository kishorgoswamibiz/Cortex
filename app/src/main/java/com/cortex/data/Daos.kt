package com.cortex.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CortexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: NodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<NodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdge(edge: EdgeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdges(edges: List<EdgeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCapture(capture: CaptureEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(embedding: EmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>)

    @Query("SELECT * FROM nodes WHERE id = :id")
    suspend fun getNodeById(id: String): NodeEntity?

    @Query("SELECT * FROM nodes WHERE id IN (:ids)")
    suspend fun getNodesByIds(ids: List<String>): List<NodeEntity>

    @Query("SELECT * FROM captures WHERE status = 'pending' ORDER BY created_at ASC")
    suspend fun getPendingCaptures(): List<CaptureEntity>

    @Query("SELECT * FROM captures ORDER BY created_at DESC LIMIT 1")
    fun getLatestCaptureFlow(): Flow<CaptureEntity?>

    @Query("SELECT COUNT(*) FROM captures WHERE status = 'pending'")
    fun getPendingCountFlow(): Flow<Int>

    // --- Curation (FR-9) ---

    @Query("""
        UPDATE nodes
        SET name = :name, summary = :summary, domain = :domain, updated_at = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateNode(id: String, name: String, summary: String, domain: String?, updatedAt: Long)

    @Query("DELETE FROM nodes WHERE id = :id")
    suspend fun deleteNode(id: String)

    @Query("UPDATE items SET content = :content, status = :status WHERE id = :id")
    suspend fun updateItem(id: String, content: String, status: String?)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("DELETE FROM embeddings WHERE owner_type = :ownerType AND owner_id = :ownerId")
    suspend fun deleteEmbedding(ownerType: String, ownerId: String)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getItemById(id: String): ItemEntity?

    @Query("UPDATE captures SET status = :status, processed_at = :processedAt WHERE id = :id")
    suspend fun updateCaptureStatus(id: String, status: String, processedAt: Long)

    @Query("""
        SELECT * FROM nodes
        WHERE LOWER(name) LIKE '%' || LOWER(:query) || '%'
           OR LOWER(summary) LIKE '%' || LOWER(:query) || '%'
        LIMIT :limit
    """)
    suspend fun searchNodesLike(query: String, limit: Int = 20): List<NodeEntity>

    @Query("SELECT * FROM nodes ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getRecentNodes(limit: Int = 50): List<NodeEntity>

    @Query("SELECT * FROM embeddings WHERE owner_type = :ownerType")
    suspend fun getEmbeddingsByOwnerType(ownerType: String): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE owner_type = :ownerType AND owner_id = :ownerId")
    suspend fun getEmbedding(ownerType: String, ownerId: String): EmbeddingEntity?

    @Query("SELECT * FROM nodes")
    fun getAllNodesFlow(): Flow<List<NodeEntity>>

    // --- Browse + read pipeline ---

    @Query("SELECT * FROM nodes WHERE parent_id IS NULL ORDER BY updated_at DESC")
    fun getRootNodesFlow(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE parent_id = :parentId ORDER BY name ASC")
    suspend fun getChildren(parentId: String): List<NodeEntity>

    @Query("""
        SELECT * FROM items
        WHERE node_id = :nodeId
          AND (:domain IS NULL OR domain = :domain OR :domain = 'mixed')
        ORDER BY created_at DESC
    """)
    suspend fun getItemsForNode(nodeId: String, domain: String? = null): List<ItemEntity>

    @Query("""
        SELECT * FROM edges
        WHERE (source_node_id = :nodeId OR target_node_id = :nodeId)
          AND (:domain IS NULL OR domain = :domain OR :domain = 'mixed')
    """)
    suspend fun getEdgesForNode(nodeId: String, domain: String? = null): List<EdgeEntity>

    @Query("""
        SELECT DISTINCT n.* FROM nodes n
        JOIN edges e
          ON (e.target_node_id = n.id AND e.source_node_id IN (:seedIds))
          OR (e.source_node_id = n.id AND e.target_node_id IN (:seedIds))
        WHERE (:domain IS NULL OR e.domain = :domain OR :domain = 'mixed')
    """)
    suspend fun expandOneHop(seedIds: List<String>, domain: String? = null): List<NodeEntity>

    @Query("""
        SELECT * FROM nodes
        WHERE (LOWER(name) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(summary) LIKE '%' || LOWER(:query) || '%')
          AND (:domain IS NULL OR domain = :domain OR domain IS NULL OR :domain = 'mixed')
        LIMIT :limit
    """)
    suspend fun searchNodesScoped(query: String, domain: String?, limit: Int = 20): List<NodeEntity>

    @Query("""
        SELECT * FROM items
        WHERE LOWER(content) LIKE '%' || LOWER(:query) || '%'
          AND (:domain IS NULL OR domain = :domain OR :domain = 'mixed')
        LIMIT :limit
    """)
    suspend fun searchItemsScoped(query: String, domain: String?, limit: Int = 30): List<ItemEntity>

    // --- Hybrid retrieval helpers (Phase 3) ---

    /** Lightweight projection of every node for in-Kotlin fuzzy scoring (graph is small). */
    @Query("SELECT id, name, type, phonetic_key AS phoneticKey FROM nodes LIMIT :cap")
    suspend fun getNodeNameKeys(cap: Int = 5000): List<NodeNameKey>

    @Query("SELECT * FROM nodes WHERE phonetic_key IN (:keys) LIMIT :limit")
    suspend fun searchNodesByPhonetic(keys: List<String>, limit: Int = 10): List<NodeEntity>

    @Query("SELECT * FROM embeddings WHERE owner_type = :ownerType AND model_version <> :modelVersion")
    suspend fun getEmbeddingsByModelVersionNot(ownerType: String, modelVersion: String): List<EmbeddingEntity>

    @Query("SELECT COUNT(*) FROM embeddings WHERE owner_type = 'node' AND model_version <> :modelVersion")
    suspend fun countNodeEmbeddingsNotVersion(modelVersion: String): Int

    // --- Alias learning (Phase 3) ---

    @Query("""
        SELECT n.* FROM nodes n
        JOIN node_aliases a ON a.node_id = n.id
        WHERE a.alias_norm = :norm
        ORDER BY a.hit_count DESC
        LIMIT :limit
    """)
    suspend fun searchNodesByAlias(norm: String, limit: Int = 10): List<NodeEntity>

    @Query("SELECT node_id FROM node_aliases WHERE alias_norm = :norm")
    suspend fun aliasOwners(norm: String): List<String>

    @Query("SELECT alias_surface FROM node_aliases WHERE node_id = :nodeId ORDER BY hit_count DESC LIMIT :limit")
    suspend fun getAliasSurfaces(nodeId: String, limit: Int = 5): List<String>

    @Query("""
        INSERT INTO node_aliases(node_id, alias_norm, alias_surface, phonetic_key, hit_count, source, created_at, updated_at)
        VALUES(:nodeId, :aliasNorm, :aliasSurface, :phoneticKey, 1, :source, :now, :now)
        ON CONFLICT(node_id, alias_norm) DO UPDATE SET
            hit_count = hit_count + 1,
            alias_surface = :aliasSurface,
            updated_at = :now,
            source = CASE WHEN :source = 'confirmed' THEN 'confirmed' ELSE node_aliases.source END
    """)
    suspend fun upsertAliasBumpCount(
        nodeId: String,
        aliasNorm: String,
        aliasSurface: String,
        phoneticKey: String?,
        source: String,
        now: Long
    )

    // --- Reminders (Phase 4/5) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminders(reminders: List<ReminderEntity>)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getReminderById(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE status = 'scheduled' AND trigger_at > :now ORDER BY trigger_at ASC")
    suspend fun getScheduledFutureReminders(now: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE trigger_at >= :start AND trigger_at < :end ORDER BY trigger_at ASC")
    suspend fun getRemindersBetween(start: Long, end: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE status IN ('scheduled','fired') ORDER BY trigger_at ASC")
    fun getActiveRemindersFlow(): Flow<List<ReminderEntity>>

    @Query("UPDATE reminders SET status = :status, fired_at = :firedAt WHERE id = :id")
    suspend fun markReminderStatus(id: String, status: String, firedAt: Long?)

    @Query("UPDATE reminders SET trigger_at = :triggerAt, status = 'scheduled' WHERE id = :id")
    suspend fun updateReminderTrigger(id: String, triggerAt: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: String)

    // --- Dated items for the calendar (Phase 5) ---

    @Query("SELECT * FROM items WHERE due_at >= :start AND due_at < :end ORDER BY due_at ASC")
    suspend fun getItemsWithDueBetween(start: Long, end: Long): List<ItemEntity>

    @Query("UPDATE items SET status = :status WHERE id = :id")
    suspend fun updateItemStatus(id: String, status: String?)

    @Transaction
    suspend fun applyExtraction(
        nodes: List<NodeEntity>,
        edges: List<EdgeEntity>,
        items: List<ItemEntity>,
        embeddings: List<EmbeddingEntity>,
        captureId: String,
        processedAt: Long
    ) {
        if (nodes.isNotEmpty()) insertNodes(nodes)
        if (edges.isNotEmpty()) insertEdges(edges)
        if (items.isNotEmpty()) insertItems(items)
        if (embeddings.isNotEmpty()) insertEmbeddings(embeddings)
        updateCaptureStatus(captureId, "processed", processedAt)
    }
}
