package com.example.optimalx.data.semantic

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.SemanticChunk
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SemanticChunkHit(
    val chunkId: Long,
    val objectType: String,
    val objectId: Long,
    val parentFolderId: Long?,
    val subfolderId: Long?,
    val location: String,
    val chunkText: String,
    val chunkType: String,
    val startLine: Int?,
    val endLine: Int?,
    val score: Float,
)

data class SemanticScopeFilter(
    val subfolderId: Long? = null,
    val parentFolderId: Long? = null,
    val objectType: String? = null,
)

class SemanticIndexer(
    private val db: AppDatabase,
    private val embeddingEngine: EmbeddingEngine,
) {
    private val chunkDao get() = db.semanticChunkDao()

    suspend fun replaceObjectChunks(
        objectType: String,
        objectId: Long,
        drafts: List<SemanticChunkDraft>,
    ) {
        if (drafts.isEmpty()) {
            deleteObject(objectType, objectId)
            return
        }
        val now = System.currentTimeMillis()
        val rows = drafts.mapNotNull { draft ->
            if (draft.chunkText.isBlank()) return@mapNotNull null
            SemanticChunk(
                objectType = draft.objectType,
                objectId = draft.objectId,
                parentFolderId = draft.parentFolderId,
                subfolderId = draft.subfolderId,
                location = draft.location,
                chunkText = draft.chunkText,
                chunkType = draft.chunkType,
                startLine = draft.startLine,
                endLine = draft.endLine,
                embeddingBlob = toBlob(embeddingEngine.embed(draft.chunkText)),
                updatedAt = now,
            )
        }
        chunkDao.replaceForObject(objectType, objectId, rows)
        deleteLegacyObjectVector(objectType, objectId)
    }

    suspend fun deleteObject(objectType: String, objectId: Long) {
        chunkDao.deleteByObject(objectType, objectId)
        deleteLegacyObjectVector(objectType, objectId)
    }

    suspend fun searchChunks(
        query: String,
        limit: Int,
        scope: SemanticScopeFilter? = null,
    ): List<SemanticChunkHit> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val queryVector = embeddingEngine.embed(query)
        val rows = chunkDao.getAll()
        return rows.asSequence()
            .filter { row -> matchesScope(row, scope) }
            .mapNotNull { row ->
                val target = fromBlob(row.embeddingBlob)
                if (target.isEmpty() || target.size != queryVector.size) return@mapNotNull null
                SemanticChunkHit(
                    chunkId = row.id,
                    objectType = row.objectType,
                    objectId = row.objectId,
                    parentFolderId = row.parentFolderId,
                    subfolderId = row.subfolderId,
                    location = row.location,
                    chunkText = row.chunkText,
                    chunkType = row.chunkType,
                    startLine = row.startLine,
                    endLine = row.endLine,
                    score = cosine(queryVector, target),
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    /** Remove orphaned chunks not belonging to any current object key set after full bootstrap. */
    suspend fun deleteChunksExcept(keepChunkIds: Set<Long>) {
        if (keepChunkIds.isEmpty()) {
            chunkDao.deleteAll()
            return
        }
        val all = chunkDao.getAll()
        val orphanIds = all.map { it.id }.filter { it !in keepChunkIds }
        if (orphanIds.isNotEmpty()) {
            chunkDao.deleteExceptIds(orphanIds)
        }
    }

    suspend fun allChunkIds(): Set<Long> = chunkDao.getAll().map { it.id }.toSet()

    private fun matchesScope(row: SemanticChunk, scope: SemanticScopeFilter?): Boolean {
        if (scope == null) return true
        scope.objectType?.let { if (row.objectType != it) return false }
        scope.subfolderId?.let { sid ->
            when (row.objectType) {
                SemanticObjectType.NOTE -> if (row.objectId != sid) return false
                else -> if (row.subfolderId != sid) return false
            }
        }
        scope.parentFolderId?.let { pid ->
            if (row.parentFolderId != pid) return false
        }
        return true
    }

    private suspend fun deleteLegacyObjectVector(objectType: String, objectId: Long) {
        db.semanticVectorDao().deleteBySource(objectType, objectId)
    }

    private fun toBlob(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun fromBlob(blob: ByteArray): FloatArray {
        if (blob.isEmpty() || blob.size % 4 != 0) return FloatArray(0)
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val values = FloatArray(blob.size / 4)
        for (i in values.indices) values[i] = buffer.getFloat()
        return values
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA <= 1e-6f || normB <= 1e-6f) return 0f
        return (dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))).coerceIn(-1f, 1f)
    }
}
