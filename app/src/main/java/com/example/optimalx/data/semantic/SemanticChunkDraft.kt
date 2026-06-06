package com.example.optimalx.data.semantic

data class SemanticChunkDraft(
    val objectType: String,
    val objectId: Long,
    val parentFolderId: Long?,
    val subfolderId: Long?,
    val location: String,
    val chunkText: String,
    val chunkType: String,
    val startLine: Int?,
    val endLine: Int?,
)
