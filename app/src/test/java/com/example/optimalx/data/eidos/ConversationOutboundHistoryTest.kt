package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationOutboundHistoryTest {

    @Test
    fun excludeActiveTurn_dropsUserAndFollowingWhenAssistantStillPresent() {
        val messages = listOf(
            chatMessage(1L, "user", "hello"),
            chatMessage(2L, "eidos", "old reply"),
        )
        val kept = ConversationOutboundHistory.excludeActiveTurn(
            messages = messages,
            userText = "hello",
            excludeMessageId = 1L,
        )
        assertTrue(kept.isEmpty())
    }

    @Test
    fun excludeLatestMatchingUser_aloneDoesNotDropWhenAssistantIsLast() {
        val messages = listOf(
            chatMessage(1L, "user", "hello"),
            chatMessage(2L, "eidos", "old reply"),
        )
        val kept = ConversationOutboundHistory.excludeLatestMatchingUser(messages, "hello")
        assertEquals(2, kept.size)
    }

    @Test
    fun buildFromMessages_doesNotRestackRetriedUserTurn() {
        val outbound = ConversationOutboundHistory.buildFromMessages(
            allMessages = listOf(
                chatMessage(1L, "user", "hello"),
                chatMessage(2L, "eidos", "old reply"),
            ),
            activeUserText = "hello",
            excludeMessageId = 1L,
        )
        assertFalse(outbound.any { it.role == EidosRole.USER && it.content == "hello" })
        assertFalse(outbound.any { it.content == "old reply" })
    }

    @Test
    fun chunkContainsActiveUserTurn_matchesExactUserLine() {
        assertTrue(
            ConversationOutboundHistory.chunkContainsActiveUserTurn(
                "Title: Chat\nuser: hello\neidos: old reply",
                "hello",
            ),
        )
        assertFalse(
            ConversationOutboundHistory.chunkContainsActiveUserTurn(
                "Title: Chat\nuser: earlier\neidos: reply",
                "hello",
            ),
        )
    }

    private fun chatMessage(id: Long, role: String, content: String): ChatMessage =
        ChatMessage(
            id = id,
            conversationId = 1L,
            role = role,
            content = content,
            createdAt = id,
        )
}
