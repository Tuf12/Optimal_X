package com.example.optimalx.data.semantic

/**
 * Location-first semantic search with optional [expand_if_weak] widening.
 */
object SemanticScopeSearch {

    const val WEAK_SCORE_THRESHOLD = 0.55f

    suspend fun search(
        indexer: SemanticIndexer,
        query: String,
        limit: Int,
        scopeMode: String?,
        scopeId: Long?,
        expansionPolicy: String?,
        resolveParentFolderId: suspend (Long) -> Long?,
    ): List<SemanticChunkHit> {
        val policy = expansionPolicy?.trim()?.lowercase().orEmpty().ifBlank { "expand_if_weak" }
        val mode = scopeMode?.trim()?.lowercase().orEmpty().ifBlank { "global" }

        val scopeChain = buildScopeChain(mode, scopeId, resolveParentFolderId)
        val merged = linkedMapOf<Long, SemanticChunkHit>()

        for (filter in scopeChain) {
            val hits = indexer.searchChunks(query, limit = limit * 3, scope = filter)
            hits.forEach { hit -> merged[hit.chunkId] = hit }
            val topScore = hits.maxOfOrNull { it.score } ?: 0f
            if (policy == "none" || topScore >= WEAK_SCORE_THRESHOLD || merged.size >= limit) {
                break
            }
        }

        return merged.values
            .sortedByDescending { it.score }
            .take(limit)
    }

    private suspend fun buildScopeChain(
        mode: String,
        scopeId: Long?,
        resolveParentFolderId: suspend (Long) -> Long?,
    ): List<SemanticScopeFilter?> {
        return when (mode) {
            "subfolder", "local_only", "local_first" -> {
                if (scopeId == null) return listOf(null)
                val parentId = resolveParentFolderId(scopeId)
                buildList {
                    add(SemanticScopeFilter(subfolderId = scopeId))
                    if (mode == "local_first" && parentId != null) {
                        add(SemanticScopeFilter(parentFolderId = parentId))
                    }
                    if (mode == "local_first") add(null)
                }
            }
            "parent", "current_parent", "current_branch" -> {
                if (scopeId == null) return listOf(null)
                listOf(
                    SemanticScopeFilter(parentFolderId = scopeId),
                    null,
                )
            }
            "conversation", "chat_history" -> listOf(
                SemanticScopeFilter(objectType = SemanticObjectType.CONVERSATION),
                null,
            )
            else -> listOf(null)
        }
    }
}
