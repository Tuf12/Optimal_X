package com.example.optimalx.data.eidos.prefetch

import com.example.optimalx.data.semantic.SemanticChunkHit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosRetrievedContextBlockTest {

    @Test
    fun format_empty_returnsNull() {
        assertNull(EidosRetrievedContextBlock.format(emptyList(), maxChars = 1000, maxChunks = 3))
    }

    @Test
    fun format_includesSectionHeaderAndTags() {
        val block = EidosRetrievedContextBlock.format(
            chunks = listOf(
                RetrievedChunk(
                    hit = sampleHit(score = 0.82f, text = "User lives in Chicago"),
                    sourceTag = "LTM",
                ),
            ),
            maxChars = 2000,
            maxChunks = 6,
        )
        assertNotNull(block)
        assertTrue(block!!.contains(EidosRetrievedContextBlock.SECTION_HEADER))
        assertTrue(block.contains("LTM"))
        assertTrue(block.contains("Chicago"))
    }

    private fun sampleHit(score: Float, text: String) = SemanticChunkHit(
        chunkId = 1L,
        objectType = "note",
        objectId = 2L,
        parentFolderId = 3L,
        subfolderId = 4L,
        location = "Eidos Memory / 2026-07-08",
        chunkText = text,
        chunkType = "paragraph",
        startLine = 1,
        endLine = 1,
        score = score,
    )
}
