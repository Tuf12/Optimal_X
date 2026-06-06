package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopIntakeSummaryTest {

    @Test
    fun fromChatMessages_joinsUserMessagesOnly() {
        val messages = listOf(
            ChatMessage(conversationId = 1L, role = "user", content = "I want a bid calculator."),
            ChatMessage(conversationId = 1L, role = "eidos", content = "What fields do you need?"),
            ChatMessage(conversationId = 1L, role = "user", content = "Rooms, labor, and markup."),
        )
        val summary = WorkshopIntakeSummary.fromChatMessages(messages)
        assertTrue(summary.contains("bid calculator"))
        assertTrue(summary.contains("Rooms, labor"))
        assertTrue(!summary.contains("What fields"))
    }

    @Test
    fun fromUserTexts_respectsMaxChars() {
        val long = "x".repeat(100)
        val summary = WorkshopIntakeSummary.fromUserTexts(listOf(long), maxChars = 20)
        assertEquals(20, summary.length)
    }
}
