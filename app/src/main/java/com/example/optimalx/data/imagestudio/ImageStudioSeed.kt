package com.example.optimalx.data.imagestudio

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.optimalx.data.dao.NoteDao
import com.example.optimalx.data.dao.ParentFolderDao
import com.example.optimalx.data.dao.SubfolderDao
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.sync.SyncContentHash
import com.example.optimalx.data.sync.SyncGlobalIds

/**
 * Seeds the Image Studio system parent and General subfolder (desktop parity).
 */
object ImageStudioSeed {

    const val GENERAL_SUBFOLDER_NAME: String = "General"

    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE file_references ADD COLUMN metadataJson TEXT DEFAULT NULL",
        )
        ensureSystemFoldersSql(db)
    }

    suspend fun ensureSeeded(database: AppDatabase) {
        ensureParent(database.parentFolderDao())
        ensureGeneralSubfolder(
            parentFolderDao = database.parentFolderDao(),
            subfolderDao = database.subfolderDao(),
            noteDao = database.noteDao(),
        )
    }

    private fun ensureSystemFoldersSql(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        val parentGlobalId = SyncGlobalIds.IMAGE_STUDIO_PARENT

        db.query(
            "SELECT id FROM parent_folders WHERE globalId = ? LIMIT 1",
            arrayOf(parentGlobalId),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                ensureGeneralSubfolderSql(db, cursor.getLong(0), now)
                return
            }
        }

        db.execSQL(
            """
            INSERT INTO parent_folders (
                name, createdAt, updatedAt, sortOrder, deletedAt, isSystemFolder, globalId, originDeviceId
            ) VALUES (?, ?, ?, ?, NULL, 1, ?, NULL)
            """.trimIndent(),
            arrayOf<Any>(
                SystemFolderNames.IMAGE_STUDIO,
                now,
                now,
                IMAGE_STUDIO_PARENT_SORT_ORDER,
                parentGlobalId,
            ),
        )

        val parentId = db.query(
            "SELECT id FROM parent_folders WHERE globalId = ? LIMIT 1",
            arrayOf(parentGlobalId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            cursor.getLong(0)
        }

        ensureGeneralSubfolderSql(db, parentId, now)
    }

    private fun ensureGeneralSubfolderSql(db: SupportSQLiteDatabase, parentId: Long, now: Long) {
        val generalGlobalId = SyncGlobalIds.IMAGE_STUDIO_GENERAL_SUBFOLDER
        db.query(
            "SELECT id FROM subfolders WHERE globalId = ? AND deletedAt IS NULL LIMIT 1",
            arrayOf(generalGlobalId),
        ).use { cursor ->
            if (cursor.moveToFirst()) return
        }

        db.execSQL(
            """
            INSERT INTO subfolders (
                parentFolderId, name, createdAt, updatedAt, sortOrder, deletedAt,
                isSystemSubfolder, projectSummary, projectSummaryUpdatedAt, targetPlatform,
                globalId, originDeviceId
            ) VALUES (?, ?, ?, ?, 0, NULL, 1, NULL, NULL, 'mobile', ?, NULL)
            """.trimIndent(),
            arrayOf<Any>(
                parentId,
                GENERAL_SUBFOLDER_NAME,
                now,
                now,
                generalGlobalId,
            ),
        )

        val subfolderId = db.query(
            "SELECT id FROM subfolders WHERE globalId = ? LIMIT 1",
            arrayOf(generalGlobalId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            cursor.getLong(0)
        }

        val emptyContent = ""
        val contentHash = SyncContentHash.noteContentHash(emptyContent)
        val noteGlobalId = java.util.UUID.randomUUID().toString()
        db.execSQL(
            """
            INSERT INTO notes (
                subfolderId, content, createdAt, updatedAt, deletedAt, aiLocked, aiBlind,
                summary, summaryChunksJson, summaryUpdatedAt, summaryContentWatermark,
                globalId, originDeviceId, contentHash
            ) VALUES (?, ?, ?, ?, NULL, 0, 0, NULL, NULL, NULL, NULL, ?, NULL, ?)
            """.trimIndent(),
            arrayOf<Any>(
                subfolderId,
                emptyContent,
                now,
                now,
                noteGlobalId,
                contentHash,
            ),
        )
    }

    private suspend fun ensureParent(parentFolderDao: ParentFolderDao) {
        val existing = parentFolderDao.getByGlobalId(SyncGlobalIds.IMAGE_STUDIO_PARENT)
            ?: parentFolderDao.getSystemFolderByName(SystemFolderNames.IMAGE_STUDIO)
        if (existing != null) return

        parentFolderDao.insert(
            ParentFolder(
                name = SystemFolderNames.IMAGE_STUDIO,
                sortOrder = IMAGE_STUDIO_PARENT_SORT_ORDER,
                isSystemFolder = true,
                globalId = SyncGlobalIds.IMAGE_STUDIO_PARENT,
            ),
        )
    }

    private suspend fun ensureGeneralSubfolder(
        parentFolderDao: ParentFolderDao,
        subfolderDao: SubfolderDao,
        noteDao: NoteDao,
    ) {
        val parent = parentFolderDao.getByGlobalId(SyncGlobalIds.IMAGE_STUDIO_PARENT)
            ?: parentFolderDao.getSystemFolderByName(SystemFolderNames.IMAGE_STUDIO)
            ?: return

        val existing = subfolderDao.getByGlobalId(SyncGlobalIds.IMAGE_STUDIO_GENERAL_SUBFOLDER)
        if (existing != null) return

        val now = System.currentTimeMillis()
        val subfolderId = subfolderDao.insert(
            Subfolder(
                parentFolderId = parent.id,
                name = GENERAL_SUBFOLDER_NAME,
                createdAt = now,
                updatedAt = now,
                isSystemSubfolder = true,
                globalId = SyncGlobalIds.IMAGE_STUDIO_GENERAL_SUBFOLDER,
            ),
        )

        noteDao.insert(
            Note(
                subfolderId = subfolderId,
                content = "",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** Sort order after Panel Workshop in [com.example.optimalx.data.db.DatabaseSeed]. */
    private const val IMAGE_STUDIO_PARENT_SORT_ORDER: Int = 8
}
