package com.cortex.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.ColumnInfo
import androidx.room.Index

@Entity(
    tableName = "nodes",
    foreignKeys = [
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("parent_id"),
        Index("phonetic_key")
    ]
)
data class NodeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val summary: String,
    val type: String, // person | project | topic | hobby | idea
    val domain: String?, // work | personal | mixed | null
    @ColumnInfo(name = "parent_id") val parentId: String?,
    // Double-Metaphone primary key of `name`, filled on write; powers phonetic recall.
    @ColumnInfo(name = "phonetic_key") val phoneticKey: String? = null,
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
    ],
    indices = [
        Index("source_node_id"),
        Index("target_node_id")
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
    ],
    indices = [
        Index("node_id"),
        Index("related_node_id"),
        Index("due_at"),
        Index("remind_at")
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
    // --- Temporal anchoring (v2). All epoch millis (UTC); null when no date was found. ---
    @ColumnInfo(name = "due_at") val dueAt: Long? = null,             // when the thing is due
    @ColumnInfo(name = "remind_at") val remindAt: Long? = null,       // when to fire an alarm (if any)
    @ColumnInfo(name = "temporal_precision") val temporalPrecision: String? = null, // exact | day | approx
    @ColumnInfo(name = "temporal_phrase") val temporalPhrase: String? = null,       // original "next Tuesday"
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "captures")
data class CaptureEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "raw_text") val rawText: String,
    val source: String, // voice | paste
    val status: String, // pending | processed | failed
    // IANA zone id captured at the moment of capture so relative dates can be
    // resolved against the user's wall-clock even if the worker runs much later.
    @ColumnInfo(name = "zone_id") val zoneId: String = "UTC",
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

/**
 * A scheduled reminder that fires an exact alarm + notification. Created by the
 * extraction pipeline when a capture carries reminder intent (or a dated task),
 * and editable from the calendar UI.
 */
@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["related_node_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("trigger_at"),
        Index("status"),
        Index("related_node_id")
    ]
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val title: String,                                  // what the notification shows
    val body: String?,                                  // optional longer line
    @ColumnInfo(name = "trigger_at") val triggerAt: Long, // epoch millis (UTC) to fire
    val status: String,                                 // scheduled | fired | done | dismissed | cancelled
    val recurrence: String? = null,                     // RRULE-lite hint; not scheduled in v2
    @ColumnInfo(name = "related_node_id") val relatedNodeId: String? = null,
    @ColumnInfo(name = "related_item_id") val relatedItemId: String? = null,
    @ColumnInfo(name = "source_capture_id") val sourceCaptureId: String? = null,
    @ColumnInfo(name = "time_zone") val timeZone: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "fired_at") val firedAt: Long? = null
)

/**
 * Learned surface forms ("my friend Ramesh", "Rmaesh") tied to a canonical node,
 * so future paraphrases/typos resolve deterministically. Accuracy compounds over time.
 */
@Entity(
    tableName = "node_aliases",
    primaryKeys = ["node_id", "alias_norm"],
    foreignKeys = [
        ForeignKey(
            entity = NodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("alias_norm"),
        Index("phonetic_key")
    ]
)
data class NodeAliasEntity(
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "alias_norm") val aliasNorm: String,       // normalized key (PK component)
    @ColumnInfo(name = "alias_surface") val aliasSurface: String, // last observed raw form, for display
    @ColumnInfo(name = "phonetic_key") val phoneticKey: String?,
    @ColumnInfo(name = "hit_count") val hitCount: Int,
    val source: String,                                            // observed | confirmed | canonical
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
