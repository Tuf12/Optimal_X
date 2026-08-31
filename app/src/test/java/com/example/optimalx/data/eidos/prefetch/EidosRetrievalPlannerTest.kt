package com.example.optimalx.data.eidos.prefetch

import com.example.optimalx.data.eidos.EidosSystemFeatureFlags
import com.example.optimalx.data.eidos.prompt.EidosScopeProfileIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosRetrievalPlannerTest {

    @Test
    fun generalApp_memoryNoteAndConversationPasses() {
        val passes = EidosRetrievalPlanner.plan(EidosScopeProfileIds.GENERAL_APP, null, null)
        val expectedMemoryPasses = if (EidosSystemFeatureFlags.JOURNAL_ENABLED) 3 else 2
        assertEquals(expectedMemoryPasses + 2, passes.size)
        assertTrue(passes.take(expectedMemoryPasses).all { it.scopeMode == "global" })
        val expectedCorpora = buildSet {
            add(EidosMemoryCorpus.DAILY)
            add(EidosMemoryCorpus.LTM)
            if (EidosSystemFeatureFlags.JOURNAL_ENABLED) add(EidosMemoryCorpus.JOURNAL)
        }
        assertEquals(
            expectedCorpora,
            passes.take(expectedMemoryPasses).map { it.corpora.single() }.toSet(),
        )
        val notePass = passes[expectedMemoryPasses]
        assertEquals("global", notePass.scopeMode)
        assertEquals(setOf(EidosMemoryCorpus.NOTE), notePass.corpora)
        assertEquals(3, notePass.perPassLimit)
        val chatPass = passes.last()
        assertEquals("chat_history", chatPass.scopeMode)
        assertEquals(setOf(EidosMemoryCorpus.CHAT), chatPass.corpora)
        assertEquals(3, chatPass.perPassLimit)
    }

    @Test
    fun subfolder_includesLocalFirstMemoryAndConversation() {
        val passes = EidosRetrievalPlanner.plan(EidosScopeProfileIds.SUBFOLDER, subfolderId = 42L, null)
        val expectedMemoryPasses = if (EidosSystemFeatureFlags.JOURNAL_ENABLED) 3 else 2
        assertEquals(expectedMemoryPasses + 2, passes.size)
        assertEquals("local_first", passes[0].scopeMode)
        assertEquals(42L, passes[0].scopeId)
        assertEquals(expectedMemoryPasses, passes.count { it.scopeMode == "global" })
        assertEquals("chat_history", passes.last().scopeMode)
    }

    @Test
    fun workshopChat_localFirstPass() {
        val passes = EidosRetrievalPlanner.plan(EidosScopeProfileIds.WORKSHOP_CHAT, subfolderId = 11L, null)
        assertEquals(1, passes.size)
        assertEquals("local_first", passes.single().scopeMode)
        assertEquals(11L, passes.single().scopeId)
        assertEquals(4, passes.single().perPassLimit)
    }

    @Test
    fun workshopEdit_localFirstPassWithHigherLimit() {
        val passes = EidosRetrievalPlanner.plan(EidosScopeProfileIds.WORKSHOP_EDIT, subfolderId = 11L, null)
        assertEquals(6, passes.single().perPassLimit)
    }

    @Test
    fun widgetAsk_memoryNoteAndConversationPasses() {
        val passes = EidosRetrievalPlanner.plan(EidosScopeProfileIds.WIDGET_ASK, null, null)
        val expectedMemoryPasses = if (EidosSystemFeatureFlags.JOURNAL_ENABLED) 3 else 2
        assertEquals(expectedMemoryPasses + 2, passes.size)
        assertEquals(expectedMemoryPasses, passes.take(expectedMemoryPasses).count { it.scopeMode == "global" && it.perPassLimit == 2 })
        val expectedCorpora = buildSet {
            add(EidosMemoryCorpus.DAILY)
            add(EidosMemoryCorpus.LTM)
            if (EidosSystemFeatureFlags.JOURNAL_ENABLED) add(EidosMemoryCorpus.JOURNAL)
        }
        assertEquals(
            expectedCorpora,
            passes.take(expectedMemoryPasses).map { it.corpora.single() }.toSet(),
        )
        val notePass = passes[expectedMemoryPasses]
        assertEquals(setOf(EidosMemoryCorpus.NOTE), notePass.corpora)
        assertEquals(2, notePass.perPassLimit)
        val chatPass = passes.last()
        assertEquals("chat_history", chatPass.scopeMode)
        assertEquals(2, chatPass.perPassLimit)
    }

    @Test
    fun generalApp_withConversationId_includesActiveConversationPass() {
        val passes = EidosRetrievalPlanner.plan(
            EidosScopeProfileIds.GENERAL_APP,
            subfolderId = null,
            parentFolderId = null,
            conversationId = 99L,
        )
        assertEquals("active_conversation", passes.first().scopeMode)
        assertEquals(99L, passes.first().scopeId)
    }

    @Test
    fun workshopEdit_withConversationId_includesActiveAndLocalFirst() {
        val passes = EidosRetrievalPlanner.plan(
            EidosScopeProfileIds.WORKSHOP_EDIT,
            subfolderId = 11L,
            parentFolderId = null,
            conversationId = 5L,
        )
        assertEquals(2, passes.size)
        assertEquals("active_conversation", passes.first().scopeMode)
        assertEquals(5L, passes.first().scopeId)
        assertEquals("local_first", passes.last().scopeMode)
    }
}
