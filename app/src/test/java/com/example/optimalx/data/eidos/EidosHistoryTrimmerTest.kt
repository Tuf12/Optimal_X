package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosRequestPhase
import com.example.optimalx.data.eidos.model.EidosRole
import com.example.optimalx.data.eidos.model.EidosToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosHistoryTrimmerTest {

    @Test
    fun trimHistoryIfNeeded_dropsOldestUserExchangeFirst() {
        val history = listOf(
            user("old"),
            assistant("old reply"),
            user("keep"),
            assistant("keep reply"),
        )
        val budget = EidosContextLimits.HistoryBudget(maxUserExchanges = 1, maxChars = 100_000)
        val trimmed = EidosHistoryTrimmer.trimHistoryIfNeeded(history, budget)
        assertEquals(2, trimmed.size)
        assertEquals("keep", trimmed.first().content)
    }

    @Test
    fun trimHistoryIfNeeded_keepsAssistantToolRoundIntact() {
        val history = listOf(
            user("old"),
            assistant("old"),
            user("q"),
            assistant("", toolCalls = listOf(toolCall("c1", "search_semantic"))),
            tool("c1", "results"),
        )
        val untrimmed = EidosHistoryTrimmer.trimHistoryIfNeeded(
            history,
            EidosContextLimits.HistoryBudget(maxUserExchanges = 8, maxChars = 100_000),
        )
        assertEquals(5, untrimmed.size)

        val trimmed = EidosHistoryTrimmer.trimHistoryIfNeeded(
            history,
            EidosContextLimits.HistoryBudget(maxUserExchanges = 1, maxChars = 100_000),
        )
        assertEquals(3, trimmed.size)
        assertEquals(EidosRole.USER, trimmed.first().role)
        assertEquals("q", trimmed.first().content)
        assertTrue(trimmed[1].assistantToolCalls.isNotEmpty())
        assertEquals(EidosRole.TOOL, trimmed.last().role)
    }

    @Test
    fun prepareKimiOutboundHistory_fullPhaseStripsTextOnlyReasoning() {
        val history = listOf(
            user("hi"),
            assistant("hey", reasoning = "thought A"),
            user("again"),
            assistant("sure", reasoning = "thought B"),
        )
        val outbound = EidosHistoryTrimmer.prepareKimiOutboundHistory(history, EidosRequestPhase.FULL)
        outbound.filter { it.role == EidosRole.ASSISTANT }.forEach {
            assertNull(it.assistantReasoningContent)
        }
    }

    @Test
    fun prepareKimiOutboundHistory_fullPhaseKeepsToolCallReasoning() {
        val history = listOf(
            user("search"),
            assistant("", reasoning = "plan search", toolCalls = listOf(toolCall("w1", "web_search"))),
            tool("w1", "results"),
            assistant("here", reasoning = "summarize"),
        )
        val outbound = EidosHistoryTrimmer.prepareKimiOutboundHistory(history, EidosRequestPhase.FULL)
        assertEquals("plan search", outbound[1].assistantReasoningContent)
        assertNull(outbound[3].assistantReasoningContent)
    }

    @Test
    fun prepareKimiOutboundHistory_toolContinuationKeepsCurrentTurnReasoningOnly() {
        val history = listOf(
            user("old"),
            assistant("old reply", reasoning = "stale"),
            user("go"),
            assistant("", reasoning = "active", toolCalls = listOf(toolCall("t1", "read_note"))),
            tool("t1", "note body"),
        )
        val outbound = EidosHistoryTrimmer.prepareKimiOutboundHistory(
            history,
            EidosRequestPhase.TOOL_CONTINUATION,
        )
        assertNull(outbound[1].assistantReasoningContent)
        assertEquals("active", outbound[3].assistantReasoningContent)
    }

    @Test
    fun prepareKimiOutboundHistory_toolContinuationWithoutUserInHistory_keepsToolReasoning() {
        val history = listOf(
            assistant("", reasoning = "web plan", toolCalls = listOf(toolCall("w1", "web_search"))),
            tool("w1", "search results"),
        )
        val outbound = EidosHistoryTrimmer.prepareKimiOutboundHistory(
            history,
            EidosRequestPhase.TOOL_CONTINUATION,
        )
        assertEquals("web plan", outbound[0].assistantReasoningContent)
    }

    @Test
    fun kimiNeedsPreservedThinking_whenTrailingToolRound() {
        assertTrue(
            EidosHistoryTrimmer.kimiNeedsPreservedThinking(
                listOf(
                    assistant("", toolCalls = listOf(toolCall("w1", "web_search"))),
                    tool("w1", "ok"),
                ),
            ),
        )
        assertFalse(
            EidosHistoryTrimmer.kimiNeedsPreservedThinking(
                listOf(user("hi"), assistant("bye")),
            ),
        )
    }

    @Test
    fun splitIntoUserExchanges_groupsToolMessagesWithUserTurn() {
        val exchanges = EidosHistoryTrimmer.splitIntoUserExchanges(
            listOf(
                user("u1"),
                assistant("a1"),
                user("u2"),
                assistant("a2", toolCalls = listOf(toolCall("x", "search_semantic"))),
                tool("x", "hit"),
            ),
        )
        assertEquals(2, exchanges.size)
        assertEquals(3, exchanges[1].size)
    }

    @Test
    fun trimHistoryIfNeeded_underBudget_returnsSameList() {
        val history = listOf(user("one"), assistant("two"))
        val budget = EidosContextLimits.historyBudget(EidosContextLimits.MEMORY_HIGH)
        val trimmed = EidosHistoryTrimmer.trimHistoryIfNeeded(history, budget)
        assertEquals(history, trimmed)
    }

    @Test
    fun isOverBudget_respectsUserExchangeLimit() {
        val history = (1..10).flatMap { i ->
            listOf(user("u$i"), assistant("a$i"))
        }
        val budget = EidosContextLimits.historyBudget(EidosContextLimits.MEMORY_LOW)
        assertTrue(EidosHistoryTrimmer.isOverBudget(history, budget))
        assertFalse(
            EidosHistoryTrimmer.isOverBudget(
                history.takeLast(16),
                budget,
            ),
        )
    }

    @Test
    fun isOverBudget_respectsCharLimit() {
        val history = listOf(
            user("x".repeat(500)),
            assistant("y".repeat(500)),
        )
        val budget = EidosContextLimits.HistoryBudget(maxUserExchanges = 100, maxChars = 400)
        assertTrue(EidosHistoryTrimmer.isOverBudget(history, budget))
        assertFalse(
            EidosHistoryTrimmer.isOverBudget(
                history,
                EidosContextLimits.HistoryBudget(maxUserExchanges = 100, maxChars = 5_000),
            ),
        )
    }

    private fun user(text: String) = EidosMessage(role = EidosRole.USER, content = text)

    private fun assistant(
        text: String,
        reasoning: String? = null,
        toolCalls: List<EidosToolCall> = emptyList(),
    ) = EidosMessage(
        role = EidosRole.ASSISTANT,
        content = text,
        assistantReasoningContent = reasoning,
        assistantToolCalls = toolCalls,
    )

    private fun tool(id: String, content: String) = EidosMessage(
        role = EidosRole.TOOL,
        content = content,
        toolCallId = id,
        toolName = "tool",
    )

    private fun toolCall(id: String, name: String) = EidosToolCall(
        id = id,
        name = name,
        argumentsJson = """{"query":"test"}""",
    )
}
