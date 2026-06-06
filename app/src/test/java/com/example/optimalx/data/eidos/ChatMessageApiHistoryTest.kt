package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageApiHistoryTest {

    @Test
    fun toEidosApiHistoryExcludingLatestUser_dropsMatchingTrailingUser() {
        val rows = listOf(
            ChatMessage(conversationId = 1L, role = "user", content = "older"),
            ChatMessage(conversationId = 1L, role = "eidos", content = "reply"),
            ChatMessage(conversationId = 1L, role = "user", content = "current"),
        )
        val history = rows.toEidosApiHistoryExcludingLatestUser("current")
        assertEquals(2, history.size)
        assertEquals(EidosRole.USER, history.first().role)
        assertEquals("older", history.first().content)
    }

    @Test
    fun ensureActiveUserTurnInHistory_appendsWhenMissing() {
        val history = mutableListOf(
            EidosMessage(role = EidosRole.USER, content = "older"),
            EidosMessage(role = EidosRole.ASSISTANT, content = "reply"),
        )
        ensureActiveUserTurnInHistory(history, "fix the calculator")
        assertEquals(3, history.size)
        assertEquals("fix the calculator", history.last().content)
    }

    @Test
    fun ensureActiveUserTurnInHistory_noDuplicateWhenAlreadyLast() {
        val history = mutableListOf(
            EidosMessage(role = EidosRole.USER, content = "same"),
        )
        ensureActiveUserTurnInHistory(history, "same")
        assertEquals(1, history.size)
    }
}
