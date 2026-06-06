package com.example.optimalx.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.optimalx.data.dao.ChatMessageDao
import com.example.optimalx.data.dao.EidosApiTraceDao
import com.example.optimalx.data.dao.ContentCheckpointDao
import com.example.optimalx.data.dao.ContentPatchDao
import com.example.optimalx.data.dao.ConversationDao
import com.example.optimalx.data.dao.CustomPanelAssignmentDao
import com.example.optimalx.data.dao.FileReferenceDao
import com.example.optimalx.data.dao.HomePinDao
import com.example.optimalx.data.dao.NoteDao
import com.example.optimalx.data.dao.PanelStateDao
import com.example.optimalx.data.dao.ParentFolderDao
import com.example.optimalx.data.dao.PendingChangeDao
import com.example.optimalx.data.dao.SemanticChunkDao
import com.example.optimalx.data.dao.SemanticVectorDao
import com.example.optimalx.data.dao.SubfolderDao
import com.example.optimalx.data.dao.TagHintLineDao
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.model.EidosApiTraceRound
import com.example.optimalx.data.model.EidosApiTraceRun
import com.example.optimalx.data.model.ContentCheckpoint
import com.example.optimalx.data.model.ContentPatch
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.model.CustomPanelAssignment
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.HomePin
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.PanelState
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.PendingChangeItem
import com.example.optimalx.data.model.PendingChangeSet
import com.example.optimalx.data.model.SemanticChunk
import com.example.optimalx.data.model.SemanticVector
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.model.TagHintLine

@Database(
    entities = [
        ParentFolder::class,
        Subfolder::class,
        Note::class,
        FileReference::class,
        SemanticVector::class,
        SemanticChunk::class,
        Conversation::class,
        ChatMessage::class,
        TagHintLine::class,
        CustomPanelAssignment::class,
        ContentCheckpoint::class,
        ContentPatch::class,
        PendingChangeSet::class,
        PendingChangeItem::class,
        HomePin::class,
        PanelState::class,
        EidosApiTraceRun::class,
        EidosApiTraceRound::class,
    ],
    version = 21,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun parentFolderDao(): ParentFolderDao
    abstract fun subfolderDao(): SubfolderDao
    abstract fun noteDao(): NoteDao
    abstract fun fileReferenceDao(): FileReferenceDao
    abstract fun semanticVectorDao(): SemanticVectorDao
    abstract fun semanticChunkDao(): SemanticChunkDao
    abstract fun conversationDao(): ConversationDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun tagHintLineDao(): TagHintLineDao
    abstract fun customPanelAssignmentDao(): CustomPanelAssignmentDao
    abstract fun contentCheckpointDao(): ContentCheckpointDao
    abstract fun contentPatchDao(): ContentPatchDao
    abstract fun pendingChangeDao(): PendingChangeDao
    abstract fun homePinDao(): HomePinDao
    abstract fun panelStateDao(): PanelStateDao
    abstract fun eidosApiTraceDao(): EidosApiTraceDao

    companion object {
        const val DATABASE_NAME = "optimalx.db"
        const val SCHEMA_VERSION = 20

        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS semantic_vectors (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceType TEXT NOT NULL,
                        sourceId INTEGER NOT NULL,
                        embeddingBlob BLOB NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_semantic_vectors_sourceType_sourceId
                    ON semantic_vectors(sourceType, sourceId)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_semantic_vectors_updatedAt
                    ON semantic_vectors(updatedAt)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // New Conversation table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        scopeType TEXT NOT NULL,
                        parentFolderId INTEGER,
                        subfolderId INTEGER,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // New ChatMessage table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // Insert a "Chats" system subfolder for every existing user parent folder
                // that does not already have one (idempotent).
                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    INSERT INTO subfolders (parentFolderId, name, createdAt, updatedAt, sortOrder, isSystemSubfolder, deletedAt)
                    SELECT pf.id, 'Chats', $now, $now, 9999, 1, NULL
                    FROM parent_folders pf
                    WHERE pf.isSystemFolder = 0
                      AND pf.deletedAt IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM subfolders s
                          WHERE s.parentFolderId = pf.id
                            AND s.name = 'Chats'
                            AND s.isSystemSubfolder = 1
                      )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    INSERT INTO parent_folders (name, createdAt, updatedAt, sortOrder, deletedAt, isSystemFolder)
                    SELECT 'Quick Notes', $now, $now, 3, NULL, 1
                    WHERE NOT EXISTS (
                        SELECT 1 FROM parent_folders
                        WHERE name = 'Quick Notes' AND isSystemFolder = 1 AND deletedAt IS NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    INSERT INTO subfolders (parentFolderId, name, createdAt, updatedAt, sortOrder, isSystemSubfolder, deletedAt)
                    SELECT pf.id, '__memory_cache__', $now, $now, 9998, 1, NULL
                    FROM parent_folders pf
                    WHERE pf.isSystemFolder = 0
                      AND pf.deletedAt IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM subfolders s
                          WHERE s.parentFolderId = pf.id
                            AND s.name = '__memory_cache__'
                            AND s.isSystemSubfolder = 1
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO notes (subfolderId, content, createdAt, updatedAt, deletedAt, aiLocked)
                    SELECT s.id, '', $now, $now, NULL, 0
                    FROM subfolders s
                    INNER JOIN parent_folders pf ON pf.id = s.parentFolderId
                    WHERE s.isSystemSubfolder = 1
                      AND s.name = '__memory_cache__'
                      AND pf.isSystemFolder = 0
                      AND pf.deletedAt IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM notes n
                          WHERE n.subfolderId = s.id
                      )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    UPDATE subfolders
                    SET name = 'Memory Cache',
                        updatedAt = $now
                    WHERE isSystemSubfolder = 1
                      AND name = '__memory_cache__'
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    INSERT INTO subfolders (parentFolderId, name, createdAt, updatedAt, sortOrder, isSystemSubfolder, deletedAt)
                    SELECT pf.id, 'Reasoning', $now, $now, 9997, 1, NULL
                    FROM parent_folders pf
                    WHERE pf.isSystemFolder = 0
                      AND pf.deletedAt IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM subfolders s
                          WHERE s.parentFolderId = pf.id
                            AND s.name = 'Reasoning'
                            AND s.isSystemSubfolder = 1
                      )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO notes (subfolderId, content, createdAt, updatedAt, deletedAt, aiLocked)
                    SELECT s.id, '', $now, $now, NULL, 0
                    FROM subfolders s
                    INNER JOIN parent_folders pf ON pf.id = s.parentFolderId
                    WHERE s.isSystemSubfolder = 1
                      AND s.name = 'Reasoning'
                      AND pf.isSystemFolder = 0
                      AND pf.deletedAt IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM notes n
                          WHERE n.subfolderId = s.id
                      )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tag_hint_lines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ref TEXT NOT NULL,
                        objectType TEXT NOT NULL,
                        scopeType TEXT NOT NULL,
                        scopeId TEXT,
                        parentRef TEXT,
                        rootBranch TEXT NOT NULL,
                        piece TEXT NOT NULL,
                        lens TEXT NOT NULL,
                        hint TEXT NOT NULL,
                        date INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tag_hint_lines_ref ON tag_hint_lines(ref)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_hint_lines_objectType ON tag_hint_lines(objectType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_hint_lines_scopeType ON tag_hint_lines(scopeType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_hint_lines_rootBranch ON tag_hint_lines(rootBranch)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_hint_lines_piece ON tag_hint_lines(piece)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_hint_lines_date ON tag_hint_lines(date)")

                val legacyTagHintSubfolderIds =
                    """
                    SELECT s.id
                    FROM subfolders s
                    INNER JOIN parent_folders p ON p.id = s.parentFolderId
                    WHERE p.name = 'Eidos Index'
                      AND p.isSystemFolder = 1
                      AND p.deletedAt IS NULL
                      AND s.name = 'Tag & Hint Index'
                      AND s.isSystemSubfolder = 1
                    """.trimIndent()
                db.execSQL("DELETE FROM notes WHERE subfolderId IN ($legacyTagHintSubfolderIds)")
                db.execSQL("DELETE FROM semantic_vectors WHERE sourceType = 'NOTE' AND sourceId IN ($legacyTagHintSubfolderIds)")
                db.execSQL("DELETE FROM subfolders WHERE id IN ($legacyTagHintSubfolderIds)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Adds the per-note "Blind" flag. When true, Eidos must not read or write this note —
                // its content is fully unavailable to API calls and tool executions.
                db.execSQL("ALTER TABLE notes ADD COLUMN aiBlind INTEGER NOT NULL DEFAULT 0")
                // Drop any cached embeddings for previously indexed notes; SemanticIndexer will rebuild
                // active vectors lazily, and blinded notes will be excluded from re-indexing.
                // (No-op if there are no rows to clean up.)
                db.execSQL(
                    """
                    DELETE FROM semantic_vectors
                    WHERE sourceType = 'note'
                      AND sourceId IN (SELECT subfolderId FROM notes WHERE aiBlind = 1)
                    """.trimIndent()
                )
                // Remove any tag-hint rows that point at a blinded note so they cannot leak content.
                db.execSQL(
                    """
                    DELETE FROM tag_hint_lines
                    WHERE objectType = 'note'
                      AND ref IN (SELECT 'note:' || id FROM notes WHERE aiBlind = 1)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN webSearchKey TEXT")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_web_editor_search
                    ON conversations(scopeType, subfolderId, webSearchKey)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_web_widget_search
                    ON conversations(scopeType, webSearchKey)
                    """.trimIndent(),
                )
            }
        }

        /**
         * Room validates migrated schema against @Entity indices (non-partial). Devices that
         * already ran an earlier 9→10 build with partial WHERE indexes need index recreation.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_conversations_web_editor_search")
                db.execSQL("DROP INDEX IF EXISTS index_conversations_web_widget_search")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_web_editor_search
                    ON conversations(scopeType, subfolderId, webSearchKey)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_web_widget_search
                    ON conversations(scopeType, webSearchKey)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN memoryDepth TEXT DEFAULT NULL",
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN summary TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE notes ADD COLUMN summaryChunksJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE notes ADD COLUMN summaryUpdatedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE subfolders ADD COLUMN projectSummary TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE subfolders ADD COLUMN projectSummaryUpdatedAt INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tag_hint_lines_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ref TEXT NOT NULL,
                        objectType TEXT NOT NULL,
                        scopeType TEXT NOT NULL,
                        scopeId TEXT,
                        parentRef TEXT,
                        rootBranch TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        hint TEXT NOT NULL,
                        objectName TEXT NOT NULL,
                        parentFolderName TEXT,
                        subfolderName TEXT,
                        date INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO tag_hint_lines_new (
                        id, ref, objectType, scopeType, scopeId, parentRef, rootBranch,
                        tag, hint, objectName, parentFolderName, subfolderName,
                        date, createdAt, updatedAt
                    )
                    SELECT
                        id, ref, objectType, scopeType, scopeId, parentRef, rootBranch,
                        CASE
                            WHEN hint IS NOT NULL AND TRIM(hint) != '' THEN SUBSTR(TRIM(hint), 1, 48)
                            ELSE 'general'
                        END,
                        hint,
                        '',
                        NULL,
                        NULL,
                        date, createdAt, updatedAt
                    FROM tag_hint_lines
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE tag_hint_lines")
                db.execSQL("ALTER TABLE tag_hint_lines_new RENAME TO tag_hint_lines")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tag_hint_lines_ref ON tag_hint_lines(ref)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_hint_lines_objectType ON tag_hint_lines(objectType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_hint_lines_scopeType ON tag_hint_lines(scopeType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_hint_lines_rootBranch ON tag_hint_lines(rootBranch)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_hint_lines_tag ON tag_hint_lines(tag)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_hint_lines_date ON tag_hint_lines(date)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS custom_panel_assignments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        workshopSubfolderId INTEGER NOT NULL,
                        targetSubfolderId INTEGER NOT NULL,
                        panelTitle TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY (workshopSubfolderId) REFERENCES subfolders(id) ON DELETE CASCADE,
                        FOREIGN KEY (targetSubfolderId) REFERENCES subfolders(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_custom_panel_assignments_workshopSubfolderId ON custom_panel_assignments(workshopSubfolderId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_custom_panel_assignments_targetSubfolderId ON custom_panel_assignments(targetSubfolderId)")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN assistantReasoningContent TEXT DEFAULT NULL",
                )
            }
        }

        /**
         * Adds the DIFF_REVIEW v1 storage tables. See `app/docs/implementation/
         * DIFF_REVIEW_IMPLEMENTATION_PLAN.md` Phase 0.
         *
         *  - content_checkpoints    sparse snapshots per workshop file (note path deferred)
         *  - content_patches        forward unified diffs between adjacent checkpoints
         *  - pending_change_sets    one review session, usually one Eidos turn
         *  - pending_change_items   per-file proposal carried by a set
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS content_checkpoints (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceType TEXT NOT NULL,
                        sourceId INTEGER NOT NULL,
                        sequence INTEGER NOT NULL,
                        contentBlob TEXT NOT NULL,
                        contentHash TEXT NOT NULL,
                        author TEXT NOT NULL,
                        label TEXT,
                        conversationId INTEGER,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_content_checkpoints_sourceType_sourceId_sequence
                    ON content_checkpoints(sourceType, sourceId, sequence)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_content_checkpoints_sourceType_sourceId_createdAt
                    ON content_checkpoints(sourceType, sourceId, createdAt)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS content_patches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceType TEXT NOT NULL,
                        sourceId INTEGER NOT NULL,
                        fromCheckpointId INTEGER NOT NULL,
                        toCheckpointId INTEGER NOT NULL,
                        unifiedDiff TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY (fromCheckpointId) REFERENCES content_checkpoints(id) ON DELETE CASCADE,
                        FOREIGN KEY (toCheckpointId) REFERENCES content_checkpoints(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_content_patches_sourceType_sourceId
                    ON content_patches(sourceType, sourceId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_content_patches_fromCheckpointId
                    ON content_patches(fromCheckpointId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_content_patches_toCheckpointId
                    ON content_patches(toCheckpointId)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_change_sets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        scopeType TEXT NOT NULL,
                        scopeId INTEGER NOT NULL,
                        conversationId INTEGER,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_pending_change_sets_scopeType_scopeId
                    ON pending_change_sets(scopeType, scopeId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_pending_change_sets_status
                    ON pending_change_sets(status)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_pending_change_sets_conversationId
                    ON pending_change_sets(conversationId)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_change_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        changeSetId INTEGER NOT NULL,
                        sourceType TEXT NOT NULL,
                        sourceId INTEGER NOT NULL,
                        baseCheckpointId INTEGER,
                        proposedContent TEXT NOT NULL,
                        proposedHash TEXT NOT NULL,
                        unifiedDiff TEXT NOT NULL,
                        status TEXT NOT NULL,
                        fileName TEXT,
                        isNewFile INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY (changeSetId) REFERENCES pending_change_sets(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_pending_change_items_changeSetId
                    ON pending_change_items(changeSetId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_pending_change_items_sourceType_sourceId
                    ON pending_change_items(sourceType, sourceId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_pending_change_items_status
                    ON pending_change_items(status)
                    """.trimIndent(),
                )
            }
        }

        /** Parent-page pinned row shortcuts. See PINNED_ROW_PANEL_GALLERY_IMPLEMENTATION_PLAN Phase 2. */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS home_pins (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        pinType TEXT NOT NULL,
                        targetId INTEGER NOT NULL,
                        displayName TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_home_pins_pinType_targetId
                    ON home_pins(pinType, targetId)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS semantic_chunks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        objectType TEXT NOT NULL,
                        objectId INTEGER NOT NULL,
                        parentFolderId INTEGER,
                        subfolderId INTEGER,
                        location TEXT NOT NULL,
                        chunkText TEXT NOT NULL,
                        chunkType TEXT NOT NULL,
                        startLine INTEGER,
                        endLine INTEGER,
                        embeddingBlob BLOB NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_semantic_chunks_objectType_objectId
                    ON semantic_chunks(objectType, objectId)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_semantic_chunks_subfolderId
                    ON semantic_chunks(subfolderId)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_semantic_chunks_parentFolderId
                    ON semantic_chunks(parentFolderId)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_semantic_chunks_updatedAt
                    ON semantic_chunks(updatedAt)
                    """.trimIndent()
                )
            }
        }

        /** Persisted panel runtime state for gallery runner and editor custom tabs. Phase 4. */
        /** Developer API trace inspector — full outbound request/response per provider round. */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS eidos_api_trace_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER,
                        conversationTitle TEXT NOT NULL,
                        directoryKey TEXT NOT NULL,
                        directoryLabel TEXT NOT NULL,
                        scopeType TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        userMessagePreview TEXT NOT NULL,
                        startedAtMillis INTEGER NOT NULL,
                        finishedAtMillis INTEGER,
                        roundCount INTEGER NOT NULL,
                        status TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_eidos_api_trace_runs_directoryKey_startedAtMillis
                    ON eidos_api_trace_runs(directoryKey, startedAtMillis)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_eidos_api_trace_runs_conversationId
                    ON eidos_api_trace_runs(conversationId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS eidos_api_trace_rounds (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId INTEGER NOT NULL,
                        roundIndex INTEGER NOT NULL,
                        phase TEXT NOT NULL,
                        requestJson TEXT NOT NULL,
                        responseJson TEXT NOT NULL,
                        recordedAtMillis INTEGER NOT NULL,
                        FOREIGN KEY(runId) REFERENCES eidos_api_trace_runs(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_eidos_api_trace_rounds_runId_roundIndex
                    ON eidos_api_trace_rounds(runId, roundIndex)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS panel_state (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        workshopSubfolderId INTEGER NOT NULL,
                        scopeKey TEXT NOT NULL,
                        stateJson TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_panel_state_workshopSubfolderId_scopeKey
                    ON panel_state(workshopSubfolderId, scopeKey)
                    """.trimIndent(),
                )
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build().also { INSTANCE = it }
            }
        }

        fun closeAndClearInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
