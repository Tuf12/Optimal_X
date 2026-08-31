package com.example.optimalx.data.eidos.prefetch

import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.ConversationOutboundHistory
import com.example.optimalx.data.eidos.EidosRetrievalGuard
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticChunkHit
import com.example.optimalx.data.semantic.SemanticIndexer
import com.example.optimalx.data.semantic.SemanticObjectType
import com.example.optimalx.data.semantic.SemanticScopeSearch

class EidosPrefetchService(
    private val database: AppDatabase,
    private val semanticIndexer: SemanticIndexer,
) {

    suspend fun prefetch(
        policy: EidosPrefetchPolicy,
        profileId: String,
        query: String,
        subfolderId: Long?,
        parentFolderId: Long?,
        excludeInlinedSubfolderId: Long? = null,
        excludeConversationId: Long? = null,
        conversationId: Long? = null,
    ): EidosPrefetchResult {
        if (!policy.profileEnabled) {
            return EidosPrefetchResult(null, EidosPrefetchMetrics.skipped("profile_off"))
        }
        if (query.isBlank()) {
            return EidosPrefetchResult(null, EidosPrefetchMetrics.skipped("empty_query"))
        }

        val passes = EidosRetrievalPlanner.plan(
            profileId = profileId,
            subfolderId = subfolderId,
            parentFolderId = parentFolderId,
            conversationId = conversationId,
        )
        if (passes.isEmpty()) {
            return EidosPrefetchResult(null, EidosPrefetchMetrics.skipped("no_passes"))
        }

        val folderIds = SystemMemoryFolderIds.load(database)
        val merged = linkedMapOf<Long, RetrievedChunk>()

        for (pass in passes) {
            val rawHits = when (pass.scopeMode) {
                "active_conversation" -> {
                    val activeId = pass.scopeId ?: continue
                    fetchActiveConversationHits(activeId, query, pass.perPassLimit)
                }
                else -> SemanticScopeSearch.search(
                    indexer = semanticIndexer,
                    query = query,
                    limit = pass.perPassLimit,
                    scopeMode = pass.scopeMode,
                    scopeId = pass.scopeId,
                    expansionPolicy = if (pass.corpora.isNotEmpty()) "none" else "expand_if_weak",
                    resolveParentFolderId = { id ->
                        database.subfolderDao().getById(id)?.parentFolderId
                    },
                )
            }
            val filtered = rawHits.filter { hit ->
                EidosPrefetchHitFilter.matchesPass(
                    hit = hit,
                    pass = pass,
                    folderIds = folderIds,
                    excludeInlinedSubfolderId = excludeInlinedSubfolderId,
                    excludeConversationId = excludeConversationId,
                ) && isHitAccessible(hit)
            }
            filtered.forEach { hit ->
                merged[hit.chunkId] = RetrievedChunk(
                    hit = hit,
                    sourceTag = EidosMemoryCorpusFilter.sourceTag(hit, folderIds),
                )
            }
        }

        val sorted = merged.values.sortedByDescending { it.hit.score }
        val topScore = sorted.firstOrNull()?.hit?.score ?: 0f
        if (sorted.isEmpty() || topScore < policy.scoreThreshold) {
            return EidosPrefetchResult(
                block = null,
                metrics = EidosPrefetchMetrics(
                    passCount = passes.size,
                    hitCount = 0,
                    topScore = topScore,
                    skippedReason = if (sorted.isEmpty()) "no_hits" else "below_threshold",
                ),
                dailyHitsInBlock = 0,
            )
        }

        val selected = EidosPrefetchChunkSelector.select(
            candidates = sorted,
            policy = policy,
            folderIds = folderIds,
        )
        val dailyHitsInBlock = selected.count { chunk ->
            EidosMemoryCorpusFilter.classify(chunk.hit, folderIds) == EidosMemoryCorpus.DAILY
        }
        val noteHitsInBlock = selected.count { chunk ->
            EidosMemoryCorpusFilter.classify(chunk.hit, folderIds) == EidosMemoryCorpus.NOTE
        }
        val block = EidosRetrievedContextBlock.format(
            chunks = selected,
            maxChars = policy.maxChars,
            maxChunks = policy.maxChunks,
        )
        return EidosPrefetchResult(
            block = block,
            metrics = EidosPrefetchMetrics(
                passCount = passes.size,
                hitCount = selected.size,
                chars = block?.length ?: 0,
                topScore = topScore,
                dailyHitsInBlock = dailyHitsInBlock,
                noteHitsInBlock = noteHitsInBlock,
            ),
            dailyHitsInBlock = dailyHitsInBlock,
        )
    }

    private suspend fun isHitAccessible(hit: SemanticChunkHit): Boolean {
        return when (hit.objectType) {
            SemanticObjectType.NOTE -> {
                val note = database.noteDao().getBySubfolderOnce(hit.objectId) ?: return false
                if (note.deletedAt != null || note.aiBlind) return false
                val subfolder = database.subfolderDao().getById(hit.objectId) ?: return false
                val parent = database.parentFolderDao().getById(subfolder.parentFolderId)
                EidosRetrievalGuard.shouldExposeNoteToEidos(subfolder, parent)
            }
            SemanticObjectType.CONVERSATION -> {
                database.conversationDao().getById(hit.objectId) != null
            }
            else -> true
        }
    }

    private suspend fun fetchActiveConversationHits(
        conversationId: Long,
        query: String,
        semanticLimit: Int,
    ): List<SemanticChunkHit> {
        val merged = linkedMapOf<Long, SemanticChunkHit>()
        val recentLimit = EidosPrefetchPolicy.ACTIVE_CONVERSATION_RECENT_CHUNKS

        // Live recent batches — indexed chunks stay stale after retry/edit truncate.
        buildLiveConversationHits(conversationId, recentLimit, query).forEach { hit ->
            merged[hit.chunkId] = hit
        }

        val semanticHits = SemanticScopeSearch.search(
            indexer = semanticIndexer,
            query = query,
            limit = semanticLimit,
            scopeMode = "active_conversation",
            scopeId = conversationId,
            expansionPolicy = "none",
            resolveParentFolderId = { id ->
                database.subfolderDao().getById(id)?.parentFolderId
            },
        )
        semanticHits
            .filterNot { ConversationOutboundHistory.chunkContainsActiveUserTurn(it.chunkText, query) }
            .forEach { hit -> merged[hit.chunkId] = hit }

        return merged.values.sortedByDescending { it.score }
    }

    private suspend fun buildLiveConversationHits(
        conversationId: Long,
        recentLimit: Int,
        activeUserText: String,
    ): List<SemanticChunkHit> {
        if (recentLimit <= 0) return emptyList()
        val chunkBuilder = SemanticChunkBuilder(
            noteDao = database.noteDao(),
            subfolderDao = database.subfolderDao(),
            parentFolderDao = database.parentFolderDao(),
            conversationDao = database.conversationDao(),
            chatMessageDao = database.chatMessageDao(),
        )
        val drafts = chunkBuilder.buildConversationChunks(
            conversationId = conversationId,
            excludeLatestUserText = activeUserText,
        )
        val recent = drafts.takeLast(recentLimit)
            .filterNot { ConversationOutboundHistory.chunkContainsActiveUserTurn(it.chunkText, activeUserText) }
        return recent.mapIndexed { index, draft ->
            SemanticChunkHit(
                chunkId = -(conversationId * 1000L + index),
                objectType = SemanticObjectType.CONVERSATION,
                objectId = conversationId,
                parentFolderId = draft.parentFolderId,
                subfolderId = draft.subfolderId,
                location = draft.location,
                chunkText = draft.chunkText,
                chunkType = draft.chunkType,
                startLine = draft.startLine,
                endLine = draft.endLine,
                score = 0.99f,
            )
        }
    }
}
