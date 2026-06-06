package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "semantic_chunks",
    indices = [
        Index(value = ["objectType", "objectId"]),
        Index(value = ["subfolderId"]),
        Index(value = ["parentFolderId"]),
        Index(value = ["updatedAt"]),
    ],
)
data class SemanticChunk(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** note | file | conversation */
    val objectType: String,
    /** note=subfolderId, file=fileReferenceId, conversation=conversationId */
    val objectId: Long,
    val parentFolderId: Long? = null,
    val subfolderId: Long? = null,
    /** Human-readable path, e.g. "Jobs / Tile work" or chat title */
    val location: String,
    val chunkText: String,
    /** heading | paragraph | line_window | thread_batch | summary */
    val chunkType: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val embeddingBlob: ByteArray,
    val updatedAt: Long = System.currentTimeMillis(),
)
