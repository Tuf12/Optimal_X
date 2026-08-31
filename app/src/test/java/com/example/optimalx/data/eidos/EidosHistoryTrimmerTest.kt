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
        val budget = EidosHistoryTrimmer.HistoryBudget(maxUserExchanges = 1, maxChars = 100_000)
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
            EidosHistoryTrimmer.HistoryBudget(maxUserExchanges = 8, maxChars = 100_000),
        )
        assertEquals(5, untrimmed.size)

        val trimmed = EidosHistoryTrimmer.trimHistoryIfNeeded(
            history,
            EidosHistoryTrimmer.HistoryBudget(maxUserExchanges = 1, maxChars = 100_000),
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
            assistant("", reasoning = "active", toolCalls = listOf(toolCall("t1", "search_semantic"))),
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
    fun prepareOutboundHistory_stubsOldReadRoundsKeepsRecent() {
        val body = "x".repeat(500)
        val history = listOf(
            user("go"),
            assistant("", toolCalls = listOf(toolCall("c1", "search_semantic"))),
            toolNamed("c1", body, "search_semantic"),
            assistant("", toolCalls = listOf(toolCall("c2", "search_semantic"))),
            toolNamed("c2", body, "search_semantic"),
            assistant("", toolCalls = listOf(toolCall("c3", "workshop_read_file"))),
            toolNamed("c3", body, "workshop_read_file"),
            assistant("", toolCalls = listOf(toolCall("c4", "search_semantic"))),
            toolNamed("c4", body, "search_semantic"),
        )
        val outbound = EidosHistoryTrimmer.prepareOutboundHistory(history, replayRounds = 3)

        assertTrue(
            "oldest read round should be stubbed",
            outbound[2].content.startsWith("[Earlier search_semantic result"),
        )
        assertTrue(outbound[2].content.contains("c1"))
        assertEquals(body, outbound[4].content)
        assertEquals(body, outbound[6].content)
        assertEquals(body, outbound[8].content)
    }

    @Test
    fun prepareOutboundHistory_neverStubsWriteResults() {
        val body = "y".repeat(500)
        val history = listOf(
            user("build"),
            assistant("", toolCalls = listOf(toolCall("w1", "workshop_write_file"))),
            toolNamed("w1", body, "workshop_write_file"),
            assistant("", toolCalls = listOf(toolCall("r2", "search_semantic"))),
            toolNamed("r2", body, "search_semantic"),
            assistant("", toolCalls = listOf(toolCall("r3", "search_semantic"))),
            toolNamed("r3", body, "search_semantic"),
            assistant("", toolCalls = listOf(toolCall("r4", "search_semantic"))),
            toolNamed("r4", body, "search_semantic"),
        )
        val outbound = EidosHistoryTrimmer.prepareOutboundHistory(history, replayRounds = 3)
        assertEquals("write result kept verbatim", body, outbound[2].content)
    }

    @Test
    fun prepareOutboundHistory_shortReadNotStubbed() {
        val history = listOf(
            assistant("", toolCalls = listOf(toolCall("c1", "search_semantic"))),
            toolNamed("c1", "tiny", "search_semantic"),
            assistant("", toolCalls = listOf(toolCall("c2", "search_semantic"))),
            toolNamed("c2", "hit", "search_semantic"),
        )
        val outbound = EidosHistoryTrimmer.prepareOutboundHistory(history, replayRounds = 1)
        assertEquals("tiny", outbound[1].content)
    }

    @Test
    fun prepareOutboundHistory_underReplayRounds_returnsSame() {
        val history = listOf(
            user("q"),
            assistant("", toolCalls = listOf(toolCall("c1", "search_semantic"))),
            toolNamed("c1", "x".repeat(500), "search_semantic"),
        )
        assertEquals(history, EidosHistoryTrimmer.prepareOutboundHistory(history, replayRounds = 3))
    }

    @Test
    fun trimHistoryIfNeeded_underBudget_returnsSameList() {
        val history = listOf(user("one"), assistant("two"))
        val budget = EidosHistoryTrimmer.HistoryBudget(maxUserExchanges = 40, maxChars = 80_000)
        val trimmed = EidosHistoryTrimmer.trimHistoryIfNeeded(history, budget)
        assertEquals(history, trimmed)
    }

    @Test
    fun isOverBudget_respectsUserExchangeLimit() {
        val history = (1..10).flatMap { i ->
            listOf(user("u$i"), assistant("a$i"))
        }
        val budget = EidosHistoryTrimmer.HistoryBudget(maxUserExchanges = 8, maxChars = 12_000)
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
        val budget = EidosHistoryTrimmer.HistoryBudget(maxUserExchanges = 100, maxChars = 400)
        assertTrue(EidosHistoryTrimmer.isOverBudget(history, budget))
        assertFalse(
            EidosHistoryTrimmer.isOverBudget(
                history,
                EidosHistoryTrimmer.HistoryBudget(maxUserExchanges = 100, maxChars = 5_000),
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

    private fun toolNamed(id: String, content: String, name: String) = EidosMessage(
        role = EidosRole.TOOL,
        content = content,
        toolCallId = id,
        toolName = name,
    )

    private fun toolCall(id: String, name: String) = EidosToolCall(
        id = id,
        name = name,
        argumentsJson = """{"query":"test"}""",
    )
}
