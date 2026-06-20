package com.cortex.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NodeEntity::class,
        EdgeEntity::class,
        ItemEntity::class,
        CaptureEntity::class,
        EmbeddingEntity::class,
        ReminderEntity::class,
        NodeAliasEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cortexDao(): CortexDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2: temporal anchoring on items, capture timezone, node phonetic key,
         * and the reminders + node_aliases tables. Additive only, so existing data
         * survives (we no longer fall back to a destructive migration).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // items: temporal columns (all nullable -> no default needed)
                db.execSQL("ALTER TABLE items ADD COLUMN due_at INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN remind_at INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN temporal_precision TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN temporal_phrase TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_due_at ON items(due_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_remind_at ON items(remind_at)")

                // captures: zone id (NOT NULL needs a default for pre-existing rows)
                db.execSQL("ALTER TABLE captures ADD COLUMN zone_id TEXT NOT NULL DEFAULT 'UTC'")

                // nodes: phonetic key + its index
                db.execSQL("ALTER TABLE nodes ADD COLUMN phonetic_key TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nodes_phonetic_key ON nodes(phonetic_key)")

                // reminders
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reminders (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        body TEXT,
                        trigger_at INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        recurrence TEXT,
                        related_node_id TEXT,
                        related_item_id TEXT,
                        source_capture_id TEXT,
                        time_zone TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        fired_at INTEGER,
                        FOREIGN KEY(related_node_id) REFERENCES nodes(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminders_trigger_at ON reminders(trigger_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminders_status ON reminders(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminders_related_node_id ON reminders(related_node_id)")

                // node_aliases
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS node_aliases (
                        node_id TEXT NOT NULL,
                        alias_norm TEXT NOT NULL,
                        alias_surface TEXT NOT NULL,
                        phonetic_key TEXT,
                        hit_count INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(node_id, alias_norm),
                        FOREIGN KEY(node_id) REFERENCES nodes(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_node_aliases_alias_norm ON node_aliases(alias_norm)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_node_aliases_phonetic_key ON node_aliases(phonetic_key)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cortex_database"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
