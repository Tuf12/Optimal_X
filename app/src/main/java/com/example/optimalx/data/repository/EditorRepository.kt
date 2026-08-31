package com.example.optimalx.data.repository

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.NoteSummaryCodec
import com.example.optimalx.data.eidos.NoteSummaryEditResult
import com.example.optimalx.data.eidos.NoteSummarySections
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.sync.SyncContentHash.withSyncFields
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticIndexer
import com.example.optimalx.data.semantic.SemanticObjectType
import com.example.optimalx.data.semantic.SemanticSyncService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed class SaveNoteSummaryResult {
    data object Success : SaveNoteSummaryResult()
    data class Failed(val message: String) : SaveNoteSummaryResult()
}

class EditorRepository(
    private val db: AppDatabase,
    private val semanticIndexer: SemanticIndexer? = null,
    private val semanticSync: SemanticSyncService? = null,
    private val semanticChunkBuilder: SemanticChunkBuilder? = null,
    private val onNoteContentSaved: (subfolderId: Long) -> Unit = {},
) {

    fun getSubfolder(id: Long): Flow<Subfolder?> = flow {
        emit(db.subfolderDao().getById(id))
    }

    fun getNote(subfolderId: Long): Flow<Note?> =
        db.noteDao().getBySubfolder(subfolderId)

    suspend fun saveNoteContent(subfolderId: Long, content: String) {
        db.noteDao().getBySubfolderOnce(subfolderId)?.let { note ->
            db.noteDao().update(note.withSyncFields(content = content))
            if (note.aiBlind) {
                semanticIndexer?.deleteObject(SemanticObjectType.NOTE, subfolderId)
            } else {
                semanticIndexer?.let { indexer ->
                    semanticChunkBuilder?.indexNote(indexer, subfolderId)
                }
            }
            requestSemanticSync("save_note_content:$subfolderId")
            onNoteContentSaved(subfolderId)
        }
    }

    suspend fun saveNoteSummary(subfolderId: Long, summaryText: String?): SaveNoteSummaryResult {
        val note = db.noteDao().getBySubfolderOnce(subfolderId)
            ?: return SaveNoteSummaryResult.Failed("Note not found.")
        val sections = NoteSummaryCodec.parse(summaryText)
        return when (val result = NoteSummaryCodec.validateAndFormat(sections)) {
            is NoteSummaryEditResult.Failure -> SaveNoteSummaryResult.Failed(result.message)
            is NoteSummaryEditResult.Success -> {
                val now = System.currentTimeMillis()
                db.noteDao().update(
                    note.withSyncFields {
                        it.copy(
                            summary = result.formatted.ifBlank { null },
                            summaryUpdatedAt = now,
                        )
                    },
                )
                if (note.aiBlind) {
                    semanticIndexer?.deleteObject(SemanticObjectType.NOTE, subfolderId)
                } else {
                    semanticIndexer?.let { indexer ->
                        semanticChunkBuilder?.indexNote(indexer, subfolderId)
                    }
                }
                requestSemanticSync("save_note_summary:$subfolderId")
                SaveNoteSummaryResult.Success
            }
        }
    }

    suspend fun saveNoteSummarySections(
        subfolderId: Long,
        sections: NoteSummarySections,
    ): SaveNoteSummaryResult {
        val formatted = NoteSummaryCodec.format(sections)
        return saveNoteSummary(subfolderId, formatted)
    }

    suspend fun toggleAiLock(subfolderId: Long) {
        db.noteDao().getBySubfolderOnce(subfolderId)?.let { note ->
            db.noteDao().update(
                note.withSyncFields { it.copy(aiLocked = !note.aiLocked) },
            )
            requestSemanticSync("toggle_ai_lock:$subfolderId")
        }
    }

    suspend fun toggleAiBlind(subfolderId: Long) {
        db.noteDao().getBySubfolderOnce(subfolderId)?.let { note ->
            val nextBlind = !note.aiBlind
            db.noteDao().update(
                note.withSyncFields { it.copy(aiBlind = nextBlind) },
            )
            if (nextBlind) {
                semanticIndexer?.deleteObject(SemanticObjectType.NOTE, subfolderId)
            } else {
                semanticIndexer?.let { indexer ->
                    semanticChunkBuilder?.indexNote(indexer, subfolderId)
                }
            }
            requestSemanticSync("toggle_ai_blind:$subfolderId")
        }
    }

    fun getFileReferences(subfolderId: Long): Flow<List<FileReference>> =
        db.fileReferenceDao().getBySubfolder(subfolderId)

    suspend fun insertFileReference(ref: FileReference): Long {
        val id = db.fileReferenceDao().insert(ref)
        requestSemanticSync("insert_file_reference:${ref.subfolderId}")
        return id
    }

    suspend fun deleteFileReference(id: Long) {
        softDeleteFileReference(id)
    }

    suspend fun softDeleteFileReference(id: Long) {
        val ts = System.currentTimeMillis()
        db.fileReferenceDao().softDelete(id, ts)
        semanticIndexer?.deleteObject(SemanticObjectType.FILE, id)
        requestSemanticSync("soft_delete_file_reference:$id")
    }

    suspend fun restoreFileReference(id: Long) {
        db.fileReferenceDao().restore(id)
        requestSemanticSync("restore_file_reference:$id")
    }

    suspend fun permanentlyDeleteFileReference(id: Long) {
        db.fileReferenceDao().deleteById(id)
        semanticIndexer?.deleteObject(SemanticObjectType.FILE, id)
        requestSemanticSync("delete_file_reference:$id")
    }

    private fun requestSemanticSync(reason: String) {
        semanticSync?.requestSync(reason)
    }
}
