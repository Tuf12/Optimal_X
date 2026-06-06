package com.example.optimalx.data.repository

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.AppIndexSyncService
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticIndexer
import com.example.optimalx.data.semantic.SemanticObjectType
import com.example.optimalx.data.semantic.SemanticSyncService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class EditorRepository(
    private val db: AppDatabase,
    private val semanticIndexer: SemanticIndexer? = null,
    private val appIndexSync: AppIndexSyncService? = null,
    private val semanticSync: SemanticSyncService? = null,
    private val semanticChunkBuilder: SemanticChunkBuilder? = null,
) {

    fun getSubfolder(id: Long): Flow<Subfolder?> = flow {
        emit(db.subfolderDao().getById(id))
    }

    fun getNote(subfolderId: Long): Flow<Note?> =
        db.noteDao().getBySubfolder(subfolderId)

    suspend fun saveNoteContent(subfolderId: Long, content: String) {
        db.noteDao().getBySubfolderOnce(subfolderId)?.let { note ->
            db.noteDao().update(
                note.copy(content = content, updatedAt = System.currentTimeMillis())
            )
            if (note.aiBlind) {
                semanticIndexer?.deleteObject(SemanticObjectType.NOTE, subfolderId)
            } else {
                semanticIndexer?.let { indexer ->
                    semanticChunkBuilder?.indexNote(indexer, subfolderId)
                }
            }
            requestIndexAndSemanticSync("save_note_content:$subfolderId")
        }
    }

    suspend fun toggleAiLock(subfolderId: Long) {
        db.noteDao().getBySubfolderOnce(subfolderId)?.let { note ->
            db.noteDao().update(
                note.copy(aiLocked = !note.aiLocked, updatedAt = System.currentTimeMillis())
            )
            requestIndexAndSemanticSync("toggle_ai_lock:$subfolderId")
        }
    }

    suspend fun toggleAiBlind(subfolderId: Long) {
        db.noteDao().getBySubfolderOnce(subfolderId)?.let { note ->
            val nextBlind = !note.aiBlind
            db.noteDao().update(
                note.copy(aiBlind = nextBlind, updatedAt = System.currentTimeMillis())
            )
            if (nextBlind) {
                semanticIndexer?.deleteObject(SemanticObjectType.NOTE, subfolderId)
            } else {
                semanticIndexer?.let { indexer ->
                    semanticChunkBuilder?.indexNote(indexer, subfolderId)
                }
            }
            requestIndexAndSemanticSync("toggle_ai_blind:$subfolderId")
        }
    }

    fun getFileReferences(subfolderId: Long): Flow<List<FileReference>> =
        db.fileReferenceDao().getBySubfolder(subfolderId)

    suspend fun insertFileReference(ref: FileReference): Long {
        val id = db.fileReferenceDao().insert(ref)
        requestIndexAndSemanticSync("insert_file_reference:${ref.subfolderId}")
        return id
    }

    suspend fun deleteFileReference(id: Long) {
        db.fileReferenceDao().deleteById(id)
        semanticIndexer?.deleteObject(SemanticObjectType.FILE, id)
        requestIndexAndSemanticSync("delete_file_reference:$id")
    }

    private fun requestIndexAndSemanticSync(reason: String) {
        appIndexSync?.requestSync(reason)
        semanticSync?.requestSync(reason)
    }
}
