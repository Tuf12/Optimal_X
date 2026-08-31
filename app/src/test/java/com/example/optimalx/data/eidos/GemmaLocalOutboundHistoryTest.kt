package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.litert.GemmaLocalPolicy
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaLocalOutboundHistoryTest {

    @Test
    fun buildForLocalGemma_ignoresThreadSummary() {
        val conversation = Conversation(
            id = 1L,
            title = "Test",
            scopeType = "general",
            threadSummary = "Long compressed history that should not reach Gemma.",
            threadSummaryCoversMessageId = 5L,
        )
        val messages = listOf(
            chatMessage(1L, 1, "user", "old"),
            chatMessage(2L, 1, "eidos", "old reply"),
            chatMessage(3L, 1, "user", "recent"),
            chatMessage(4L, 1, "eidos", "recent reply"),
            chatMessage(5L, 1, "user", "latest"),
        )
        val outbound = ConversationOutboundHistory.buildForLocalGemmaFromMessages(
            allMessages = messages,
            activeUserText = "latest",
        )
        assertFalse(outbound.any { it.content.contains("Long compressed history") })
        assertEquals(4, outbound.size)
    }

    @Test
    fun trimOutboundHistory_dropsOldestExchangesWhenOverBudget() {
        val history = mutableListOf<com.example.optimalx.data.eidos.model.EidosMessage>()
        repeat(10) { index ->
            history += com.example.optimalx.data.eidos.model.EidosMessage(
                role = EidosRole.USER,
                content = "user turn $index with some padding text",
            )
            history += com.example.optimalx.data.eidos.model.EidosMessage(
                role = EidosRole.ASSISTANT,
                content = "assistant reply $index with padding",
            )
        }
        val trimmed = GemmaLocalPolicy.trimOutboundHistory(history)
        val userTurns = trimmed.count { it.role == EidosRole.USER }
        assertTrue(userTurns <= GemmaLocalPolicy.MAX_USER_EXCHANGES)
        assertTrue(trimmed.sumOf { it.content.length } <= GemmaLocalPolicy.MAX_OUTBOUND_CHARS)
    }

  private fun chatMessage(id: Long, conversationId: Long, role: String, content: String): ChatMessage =
        ChatMessage(
            id = id,
            conversationId = conversationId,
            role = role,
            content = content,
            createdAt = id,
        )
}
