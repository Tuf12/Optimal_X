package com.example.optimalx.data.eidos

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticIndexer

class EidosSystemMemoryRepository(
    private val db: AppDatabase,
    private val semanticIndexer: SemanticIndexer,
    private val semanticChunkBuilder: SemanticChunkBuilder,
) {

    suspend fun deleteEntry(subfolderId: Long, chunkIndex: Int): Boolean {
        val note = db.noteDao().getBySubfolderOnce(subfolderId) ?: return false
        val chunks = EidosSystemMemoryFormat.splitChunks(note.content).toMutableList()
        if (chunkIndex !in chunks.indices) return false
        chunks.removeAt(chunkIndex)
        val updated = EidosSystemMemoryFormat.joinChunks(chunks)
        db.noteDao().update(
            note.copy(
                content = updated,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        semanticChunkBuilder.indexNote(semanticIndexer, subfolderId)
        return true
    }
}
