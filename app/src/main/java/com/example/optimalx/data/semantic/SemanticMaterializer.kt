package com.example.optimalx.data.semantic

import android.util.Log
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.FileTextExtractor
import com.example.optimalx.data.model.FileReference
import java.io.File

data class SemanticSyncResult(
    val upsertedCount: Int,
    val deletedCount: Int,
    val actionLabel: String,
)

/** @deprecated Use [SemanticSyncResult]. */
typealias SemanticBootstrapResult = SemanticSyncResult

class SemanticMaterializer(
    private val db: AppDatabase,
    private val semanticIndexer: SemanticIndexer,
    private val chunkBuilder: SemanticChunkBuilder,
    private val fileTextExtractor: FileTextExtractor,
) {
    suspend fun syncForReason(reason: String): SemanticSyncResult {
        return when (val action = SemanticSyncReason.parse(reason)) {
            SemanticSyncAction.FullBootstrap -> bootstrapFullEmbeddings()
            SemanticSyncAction.NoOp -> SemanticSyncResult(0, 0, "noop")
            is SemanticSyncAction.IndexNote -> {
                indexNote(action.subfolderId)
                SemanticSyncResult(1, 0, "index_note")
            }
            is SemanticSyncAction.IndexSubfolder -> {
                val count = indexSubfolder(action.subfolderId)
                SemanticSyncResult(count, 0, "index_subfolder")
            }
            is SemanticSyncAction.IndexParentFolder -> {
                val count = indexParentFolder(action.parentFolderId)
                SemanticSyncResult(count, 0, "index_parent")
            }
            is SemanticSyncAction.IndexConversation -> {
                indexConversation(action.conversationId)
                SemanticSyncResult(1, 0, "index_conversation")
            }
            is SemanticSyncAction.DeleteFile -> {
                semanticIndexer.deleteObject(SemanticObjectType.FILE, action.fileId)
                SemanticSyncResult(0, 1, "delete_file")
            }
            is SemanticSyncAction.DeleteSubfolder -> {
                val deleted = deleteSubfolderScope(action.subfolderId)
                SemanticSyncResult(0, deleted, "delete_subfolder")
            }
            is SemanticSyncAction.DeleteParentFolder -> {
                val deleted = deleteParentFolderScope(action.parentFolderId)
                SemanticSyncResult(0, deleted, "delete_parent")
            }
            is SemanticSyncAction.DeleteConversation -> {
                semanticIndexer.deleteObject(SemanticObjectType.CONVERSATION, action.conversationId)
                SemanticSyncResult(0, 1, "delete_conversation")
            }
        }
    }

    suspend fun bootstrapFullEmbeddings(): SemanticSyncResult {
        db.semanticChunkDao().deleteAll()
        db.semanticVectorDao().let { dao ->
            dao.getAll().forEach { row ->
                dao.deleteBySource(row.sourceType, row.sourceId)
            }
        }

        var upserted = 0

        db.noteDao().getAllActiveOnce().forEach { note ->
            if (note.deletedAt != null || note.aiBlind) return@forEach
            chunkBuilder.indexNote(semanticIndexer, note.subfolderId)
            upserted++
        }

        db.fileReferenceDao().getAllOnce().forEach { ref ->
            indexFileRef(ref)
            upserted++
        }

        db.conversationDao().getAllForEmbedding().forEach { conversation ->
            chunkBuilder.indexConversation(semanticIndexer, conversation.id)
            upserted++
        }

        return SemanticSyncResult(upsertedCount = upserted, deletedCount = 0, actionLabel = "full_bootstrap")
    }

    suspend fun indexNote(subfolderId: Long) {
        chunkBuilder.indexNote(semanticIndexer, subfolderId)
    }

    suspend fun indexSubfolder(subfolderId: Long): Int {
        var count = 0
        indexNote(subfolderId)
        count++
        db.fileReferenceDao().getBySubfolderOnce(subfolderId).forEach { ref ->
            indexFileRef(ref)
            count++
        }
        return count
    }

    suspend fun indexParentFolder(parentFolderId: Long): Int {
        var count = 0
        db.subfolderDao().getAllByParentOnce(parentFolderId).forEach { subfolder ->
            if (subfolder.deletedAt != null) return@forEach
            count += indexSubfolder(subfolder.id)
        }
        db.conversationDao().getRecentByParentFolder(parentFolderId, CONVERSATION_SCAN_LIMIT)
            .forEach { conversation ->
                indexConversation(conversation.id)
                count++
            }
        return count
    }

    suspend fun indexFile(ref: FileReference, extractedText: String?) {
        val text = extractedText ?: fileTextExtractor.extractText(File(ref.filePath)).orEmpty()
        if (text.isBlank()) {
            semanticIndexer.deleteObject(SemanticObjectType.FILE, ref.id)
        } else {
            chunkBuilder.indexFile(semanticIndexer, ref, text)
        }
    }

    suspend fun indexConversation(conversationId: Long) {
        chunkBuilder.indexConversation(semanticIndexer, conversationId)
    }

    private suspend fun deleteSubfolderScope(subfolderId: Long): Int {
        var deleted = 0
        semanticIndexer.deleteObject(SemanticObjectType.NOTE, subfolderId)
        deleted++
        db.fileReferenceDao().getBySubfolderOnce(subfolderId).forEach { ref ->
            semanticIndexer.deleteObject(SemanticObjectType.FILE, ref.id)
            deleted++
        }
        db.conversationDao().getRecentBySubfolder(subfolderId, CONVERSATION_SCAN_LIMIT).forEach { conversation ->
            semanticIndexer.deleteObject(SemanticObjectType.CONVERSATION, conversation.id)
            deleted++
        }
        db.semanticChunkDao().deleteBySubfolder(subfolderId)
        return deleted
    }

    private suspend fun deleteParentFolderScope(parentFolderId: Long): Int {
        var deleted = 0
        db.subfolderDao().getAllByParentOnce(parentFolderId).forEach { subfolder ->
            deleted += deleteSubfolderScope(subfolder.id)
        }
        db.conversationDao().getRecentByParentFolder(parentFolderId, CONVERSATION_SCAN_LIMIT).forEach { conversation ->
            semanticIndexer.deleteObject(SemanticObjectType.CONVERSATION, conversation.id)
            deleted++
        }
        db.semanticChunkDao().deleteByParentFolder(parentFolderId)
        return deleted
    }

    private suspend fun indexFileRef(ref: FileReference) {
        val file = File(ref.filePath)
        if (!fileTextExtractor.isExtractable(file)) {
            semanticIndexer.deleteObject(SemanticObjectType.FILE, ref.id)
            return
        }
        val extracted = fileTextExtractor.extractText(file)
        if (extracted.isNullOrBlank()) {
            Log.d(TAG, "No extractable text for file: ${ref.fileName}")
            semanticIndexer.deleteObject(SemanticObjectType.FILE, ref.id)
            return
        }
        chunkBuilder.indexFile(semanticIndexer, ref, extracted)
    }

    private companion object {
        const val TAG = "OptimalX.SemanticMaterializer"
        const val CONVERSATION_SCAN_LIMIT = 5_000
    }
}
