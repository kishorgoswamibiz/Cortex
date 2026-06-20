package com.cortex.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.ColumnInfo
@Entity(
    tableName = "nodes",
    foreignKeys = [
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class NodeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val summary: String,
    val type: String, // person | project | topic | hobby | idea
    val domain: String?, // work | personal | mixed | null
    @ColumnInfo(name = "parent_id") val parentId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "edges",
    foreignKeys = [
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_node_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class EdgeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_node_id") val sourceNodeId: String,
    @ColumnInfo(name = "target_node_id") val targetNodeId: String,
    @ColumnInfo(name = "relation_type") val relationType: String,
    val domain: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["related_node_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "node_id") val nodeId: String,
    val content: String,
    val kind: String, // fact | task | note | decision
    val domain: String,
    val status: String?, // open | done | null
    @ColumnInfo(name = "related_node_id") val relatedNodeId: String?,
    @ColumnInfo(name = "source_capture_id") val sourceCaptureId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "captures")
data class CaptureEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "raw_text") val rawText: String,
    val source: String, // voice | paste
    val status: String, // pending | processed | failed
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "processed_at") val processedAt: Long?
)

@Entity(
    tableName = "embeddings",
    primaryKeys = ["owner_type", "owner_id"]
)
data class EmbeddingEntity(
    @ColumnInfo(name = "owner_type") val ownerType: String, // node | item
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
    @ColumnInfo(name = "model_version") val modelVersion: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddingEntity
        if (ownerType != other.ownerType) return false
        if (ownerId != other.ownerId) return false
        if (!vector.contentEquals(other.vector)) return false
        if (modelVersion != other.modelVersion) return false
        return true
    }
    override fun hashCode(): Int {
        var result = ownerType.hashCode()
        result = 31 * result + ownerId.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + modelVersion.hashCode()
        return result
    }
}
