package com.example.optimalx.data.eidos.prefetch

import com.example.optimalx.data.semantic.SemanticChunkHit
import com.example.optimalx.data.semantic.SemanticObjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPrefetchChunkSelectorTest {

    private val folderIds = SystemMemoryFolderIds(
        dailyParentId = 10L,
        ltmParentId = 20L,
        journalParentId = 30L,
    )

    private val policy = EidosPrefetchPolicy(
        profileEnabled = true,
        maxChunks = 4,
        reservedNoteSlots = 2,
        reservedChatSlots = 1,
    )

    @Test
    fun reservedSlots_keepNoteAndChatDespiteLowerScores() {
        val ltm = chunk(corpusParentId = 20L, score = 0.9f, text = "ltm high")
        val journal = chunk(corpusParentId = 30L, score = 0.85f, text = "journal")
        val note = chunk(corpusParentId = 99L, score = 0.6f, text = "note fact")
        val chat = chatChunk(score = 0.58f, text = "prior chat")
        val daily = chunk(corpusParentId = 10L, score = 0.7f, text = "daily")

        val selected = EidosPrefetchChunkSelector.select(
            candidates = listOf(ltm, journal, note, chat, daily).map { toRetrieved(it) },
            policy = policy,
            folderIds = folderIds,
        )

        assertEquals(4, selected.size)
        assertTrue(selected.any { it.sourceTag == "Note" && it.hit.chunkText.contains("note fact") })
        assertTrue(selected.any { it.sourceTag == "Chat" })
        assertTrue(selected.any { it.sourceTag == "LTM" })
    }

    @Test
    fun belowThresholdChunks_areExcluded() {
        val weakNote = chunk(corpusParentId = 99L, score = 0.4f, text = "weak")
        val strongLtm = chunk(corpusParentId = 20L, score = 0.8f, text = "strong")

        val selected = EidosPrefetchChunkSelector.select(
            candidates = listOf(weakNote, strongLtm).map { toRetrieved(it) },
            policy = policy,
            folderIds = folderIds,
        )

        assertEquals(1, selected.size)
        assertEquals("LTM", selected.single().sourceTag)
    }

    private fun toRetrieved(hit: SemanticChunkHit) = RetrievedChunk(
        hit = hit,
        sourceTag = EidosMemoryCorpusFilter.sourceTag(hit, folderIds),
    )

    private fun chunk(corpusParentId: Long, score: Float, text: String) = SemanticChunkHit(
        chunkId = (score * 1000).toLong(),
        objectType = SemanticObjectType.NOTE,
        objectId = corpusParentId,
        parentFolderId = corpusParentId,
        subfolderId = corpusParentId,
        location = "path",
        chunkText = text,
        chunkType = "paragraph",
        startLine = 1,
        endLine = 1,
        score = score,
    )

    private fun chatChunk(score: Float, text: String) = SemanticChunkHit(
        chunkId = (score * 2000).toLong(),
        objectType = SemanticObjectType.CONVERSATION,
        objectId = 5L,
        parentFolderId = null,
        subfolderId = null,
        location = "Chat",
        chunkText = text,
        chunkType = "thread_batch",
        startLine = null,
        endLine = null,
        score = score,
    )
}
