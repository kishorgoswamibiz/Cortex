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
