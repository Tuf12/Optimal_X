package com.example.optimalx.data.eidos.prefetch

import com.example.optimalx.data.eidos.prefetch.EidosMemoryCorpusFilter
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticChunkHit
import com.example.optimalx.data.semantic.SemanticObjectType
import org.junit.Assert.assertEquals
import org.junit.Test

class EidosMemoryCorpusFilterTest {

    private val folderIds = SystemMemoryFolderIds(
        dailyParentId = 10L,
        ltmParentId = 20L,
        journalParentId = 30L,
    )

    @Test
    fun classify_noteBySystemParent() {
        assertEquals(
            EidosMemoryCorpus.LTM,
            EidosMemoryCorpusFilter.classify(hit(parentFolderId = 20L), folderIds),
        )
        assertEquals(
            EidosMemoryCorpus.DAILY,
            EidosMemoryCorpusFilter.classify(hit(parentFolderId = 10L), folderIds),
        )
        assertEquals(
            EidosMemoryCorpus.NOTE,
            EidosMemoryCorpusFilter.classify(hit(parentFolderId = 99L), folderIds),
        )
    }

    @Test
    fun classify_conversationIsChat() {
        val hit = SemanticChunkHit(
            chunkId = 1L,
            objectType = SemanticObjectType.CONVERSATION,
            objectId = 5L,
            parentFolderId = null,
            subfolderId = null,
            location = "General chat",
            chunkText = "hello",
            chunkType = "thread_batch",
            startLine = null,
            endLine = null,
            score = 0.7f,
        )
        assertEquals(EidosMemoryCorpus.CHAT, EidosMemoryCorpusFilter.classify(hit, folderIds))
    }

    @Test
    fun sourceTag_projectSummaryChunk() {
        val hit = SemanticChunkHit(
            chunkId = 1L,
            objectType = SemanticObjectType.NOTE,
            objectId = 5L,
            parentFolderId = 99L,
            subfolderId = 5L,
            location = "Panels / Stroke panel (project summary)",
            chunkText = "Project summary: stylus panel",
            chunkType = SemanticChunkBuilder.CHUNK_TYPE_PROJECT_SUMMARY,
            startLine = null,
            endLine = null,
            score = 0.8f,
        )
        assertEquals("Project summary", EidosMemoryCorpusFilter.sourceTag(hit, folderIds))
    }

    private fun hit(parentFolderId: Long) = SemanticChunkHit(
        chunkId = 1L,
        objectType = SemanticObjectType.NOTE,
        objectId = 2L,
        parentFolderId = parentFolderId,
        subfolderId = 3L,
        location = "path",
        chunkText = "text",
        chunkType = "paragraph",
        startLine = 1,
        endLine = 1,
        score = 0.6f,
    )
}
