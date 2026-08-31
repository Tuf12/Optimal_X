package com.example.optimalx.data.eidos

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticIndexer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Appends timestamped lines to today's note under the **Eidos Log** system parent.
 * Shared by [RoomToolExecutor.write_log_entry] and background Eidos work (rolling summaries, etc.).
 */
class EidosLogWriter(
    private val db: AppDatabase,
    private val semanticIndexer: SemanticIndexer? = null,
    private val semanticChunkBuilder: SemanticChunkBuilder? = null,
) {

    suspend fun append(
        action: String,
        timestamp: Long = System.currentTimeMillis(),
        location: String? = null,
        anchor: String? = null,
    ): Boolean {
        val parent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_LOG) ?: return false

        val subfolder = getOrCreateDailySubfolder(parent.id, timestamp)
        val note = getOrCreateDailyNote(subfolder.id)

        val line = buildString {
            append(action)
            if (!location.isNullOrBlank()) append(" | location=$location")
            if (!anchor.isNullOrBlank()) append(" | anchor=$anchor")
        }

        val updated = note.content.appendEntry(timestamp, line)
        db.noteDao().update(note.copy(content = updated, updatedAt = System.currentTimeMillis()))
        val indexer = semanticIndexer
        val chunkBuilder = semanticChunkBuilder
        if (indexer != null && chunkBuilder != null) {
            chunkBuilder.indexNote(indexer, subfolder.id)
        }
        return true
    }

    private suspend fun getOrCreateDailySubfolder(parentFolderId: Long, timestamp: Long): Subfolder {
        val dateName = dateKeyFromTimestamp(timestamp)
        val existing = db.subfolderDao().getAllByParentOnce(parentFolderId)
            .firstOrNull { it.deletedAt == null && it.name == dateName }
        if (existing != null) return existing

        val id = db.subfolderDao().insert(
            Subfolder(
                parentFolderId = parentFolderId,
                name = dateName,
                isSystemSubfolder = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return db.subfolderDao().getById(id)!!
    }

    private suspend fun getOrCreateDailyNote(subfolderId: Long): Note {
        val existing = db.noteDao().getBySubfolderOnce(subfolderId)
        if (existing != null) return existing
        val id = db.noteDao().insert(Note(subfolderId = subfolderId, updatedAt = System.currentTimeMillis()))
        return db.noteDao().getById(id)!!
    }

    private fun dateKeyFromTimestamp(timestamp: Long): String {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
    }

    private fun String.appendEntry(timestamp: Long, line: String): String {
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
        val entry = "[$ts] $line"
        return if (isBlank()) entry else "$this\n\n$entry"
    }
}
