package com.example.optimalx.data.eidos.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningTraceTest {

    @Test
    fun singleFinalHop_returnsPlainText() {
        val hops = listOf(
            EidosReasoningHop(
                round = 0,
                phase = EidosRequestPhase.FULL,
                toolNames = emptyList(),
                reasoning = "Only final thinking.",
                isFinal = true,
            ),
        )
        assertEquals("Only final thinking.", formatPersistableReasoning(hops, null))
    }

    @Test
    fun multiHopTurn_joinsWithSectionHeaders() {
        val hops = listOf(
            EidosReasoningHop(
                round = 0,
                phase = EidosRequestPhase.FULL,
                toolNames = listOf("search_semantic"),
                reasoning = "Need to search first.",
                isFinal = false,
            ),
            EidosReasoningHop(
                round = 1,
                phase = EidosRequestPhase.TOOL_CONTINUATION,
                toolNames = listOf("read_note"),
                reasoning = "Now read the note.",
                isFinal = false,
            ),
            EidosReasoningHop(
                round = 2,
                phase = EidosRequestPhase.TOOL_CONTINUATION,
                toolNames = emptyList(),
                reasoning = "Summarize for user.",
                isFinal = true,
            ),
        )
        val formatted = formatPersistableReasoning(hops, null)!!
        assertEquals(
            "### Before search_semantic\nNeed to search first.\n\n" +
                "### Before read_note\nNow read the note.\n\n" +
                "### Final answer\nSummarize for user.",
            formatted,
        )
    }

    @Test
    fun emptyTrace_fallsBackToFinalReasoning() {
        assertEquals("fallback", formatPersistableReasoning(emptyList(), "fallback"))
        assertNull(formatPersistableReasoning(emptyList(), null))
    }

    @Test
    fun persistableReasoningContent_usesTraceOnResponse() {
        val response = EidosResponse(
            textResponse = "Hello",
            assistantReasoningContent = "Final only in field",
            reasoningTrace = listOf(
                EidosReasoningHop(
                    round = 0,
                    phase = EidosRequestPhase.FULL,
                    toolNames = listOf("search_semantic"),
                    reasoning = "Hop one",
                    isFinal = false,
                ),
                EidosReasoningHop(
                    round = 1,
                    phase = EidosRequestPhase.TOOL_CONTINUATION,
                    toolNames = emptyList(),
                    reasoning = "Hop two",
                    isFinal = true,
                ),
            ),
        )
        assertEquals(
            "### Before search_semantic\nHop one\n\n### Final answer\nHop two",
            response.persistableReasoningContent(),
        )
    }
}
