package com.example.optimalx.data.eidos.prefetch

import com.example.optimalx.data.semantic.SemanticChunkHit
import com.example.optimalx.data.semantic.SemanticObjectType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPrefetchHitFilterTest {

    private val folderIds = SystemMemoryFolderIds(
        dailyParentId = 10L,
        ltmParentId = 20L,
        journalParentId = 30L,
    )

    @Test
    fun excludesInlinedSubfolderNoteHits() {
        val hit = noteHit(subfolderId = 99L, parentFolderId = 50L)
        val pass = EidosRetrievalPass(scopeMode = "local_first", scopeId = 99L)
        assertFalse(
            EidosPrefetchHitFilter.matchesPass(
                hit = hit,
                pass = pass,
                folderIds = folderIds,
                excludeInlinedSubfolderId = 99L,
            ),
        )
    }

    @Test
    fun keepsLtmHitsOnMemoryPass() {
        val hit = noteHit(subfolderId = 5L, parentFolderId = 20L)
        val pass = EidosRetrievalPass(
            scopeMode = "global",
            corpora = setOf(EidosMemoryCorpus.LTM),
        )
        assertTrue(
            EidosPrefetchHitFilter.matchesPass(
                hit = hit,
                pass = pass,
                folderIds = folderIds,
                excludeInlinedSubfolderId = null,
            ),
        )
    }

    @Test
    fun keepsUserNoteHitsOnGlobalNotePass() {
        val hit = noteHit(subfolderId = 5L, parentFolderId = 99L)
        val pass = EidosRetrievalPass(
            scopeMode = "global",
            corpora = setOf(EidosMemoryCorpus.NOTE),
        )
        assertTrue(
            EidosPrefetchHitFilter.matchesPass(
                hit = hit,
                pass = pass,
                folderIds = folderIds,
                excludeInlinedSubfolderId = null,
            ),
        )
    }

    @Test
    fun excludesLtmFromGlobalNotePass() {
        val hit = noteHit(subfolderId = 5L, parentFolderId = 20L)
        val pass = EidosRetrievalPass(
            scopeMode = "global",
            corpora = setOf(EidosMemoryCorpus.NOTE),
        )
        assertFalse(
            EidosPrefetchHitFilter.matchesPass(
                hit = hit,
                pass = pass,
                folderIds = folderIds,
                excludeInlinedSubfolderId = null,
            ),
        )
    }

    @Test
    fun keepsChatHitsOnConversationPass() {
        val hit = chatHit(conversationId = 7L)
        val pass = EidosRetrievalPass(
            scopeMode = "chat_history",
            corpora = setOf(EidosMemoryCorpus.CHAT),
        )
        assertTrue(
            EidosPrefetchHitFilter.matchesPass(
                hit = hit,
                pass = pass,
                folderIds = folderIds,
                excludeInlinedSubfolderId = null,
            ),
        )
    }

    @Test
    fun excludesActiveConversationHits() {
        val hit = chatHit(conversationId = 7L)
        val pass = EidosRetrievalPass(
            scopeMode = "chat_history",
            corpora = setOf(EidosMemoryCorpus.CHAT),
        )
        assertFalse(
            EidosPrefetchHitFilter.matchesPass(
                hit = hit,
                pass = pass,
                folderIds = folderIds,
                excludeInlinedSubfolderId = null,
                excludeConversationId = 7L,
            ),
        )
    }

    @Test
    fun activeConversationPass_includesActiveConversationHits() {
        val hit = chatHit(conversationId = 7L)
        val pass = EidosRetrievalPass(
            scopeMode = "active_conversation",
            scopeId = 7L,
            corpora = setOf(EidosMemoryCorpus.CHAT),
        )
        assertTrue(
            EidosPrefetchHitFilter.matchesPass(
                hit = hit,
                pass = pass,
                folderIds = folderIds,
                excludeInlinedSubfolderId = null,
                excludeConversationId = 7L,
            ),
        )
    }

    private fun chatHit(conversationId: Long) = SemanticChunkHit(
        chunkId = 2L,
        objectType = SemanticObjectType.CONVERSATION,
        objectId = conversationId,
        parentFolderId = null,
        subfolderId = null,
        location = "Tile estimate chat",
        chunkText = "We agreed on $4200 for the backsplash.",
        chunkType = "thread_batch",
        startLine = null,
        endLine = null,
        score = 0.8f,
    )

    private fun noteHit(subfolderId: Long, parentFolderId: Long) = SemanticChunkHit(
        chunkId = 1L,
        objectType = SemanticObjectType.NOTE,
        objectId = subfolderId,
        parentFolderId = parentFolderId,
        subfolderId = subfolderId,
        location = "path",
        chunkText = "text",
        chunkType = "paragraph",
        startLine = 1,
        endLine = 1,
        score = 0.7f,
    )
}
