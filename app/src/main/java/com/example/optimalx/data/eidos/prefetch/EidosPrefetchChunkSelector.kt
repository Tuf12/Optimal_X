package com.example.optimalx.data.eidos.prefetch

/**
 * Merges prefetch pass hits with reserved slots so note/chat corpora are not always
 * crowded out by higher-scoring memory chunks.
 */
object EidosPrefetchChunkSelector {

    fun select(
        candidates: List<RetrievedChunk>,
        policy: EidosPrefetchPolicy,
        folderIds: SystemMemoryFolderIds,
    ): List<RetrievedChunk> {
        if (candidates.isEmpty()) return emptyList()

        val maxChunks = policy.maxChunks.coerceAtLeast(1)
        val eligible = candidates
            .filter { it.hit.score >= policy.scoreThreshold }
            .sortedByDescending { it.hit.score }
        if (eligible.isEmpty()) return emptyList()

        val selectedIds = linkedSetOf<Long>()
        val selected = mutableListOf<RetrievedChunk>()

        fun takeFrom(corpus: EidosMemoryCorpus, limit: Int) {
            if (limit <= 0) return
            eligible
                .asSequence()
                .filter { chunk ->
                    EidosMemoryCorpusFilter.classify(chunk.hit, folderIds) == corpus
                }
                .take(limit)
                .forEach { chunk ->
                    if (selectedIds.add(chunk.hit.chunkId)) {
                        selected.add(chunk)
                    }
                }
        }

        takeFrom(EidosMemoryCorpus.NOTE, policy.reservedNoteSlots)
        takeFrom(EidosMemoryCorpus.CHAT, policy.reservedChatSlots)

        for (chunk in eligible) {
            if (selected.size >= maxChunks) break
            if (selectedIds.add(chunk.hit.chunkId)) {
                selected.add(chunk)
            }
        }

        return selected
            .sortedByDescending { it.hit.score }
            .take(maxChunks)
    }
}
