package com.example.optimalx.data.eidos.model

import com.example.optimalx.data.eidos.ReasoningPersistPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("Only final thinking.", formatArchivedReasoningTrace(hops, null))
    }

    @Test
    fun multiHopArchive_joinsWithSectionHeaders() {
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
        val formatted = formatArchivedReasoningTrace(hops, null)!!
        assertEquals(
            "### Before search_semantic\nNeed to search first.\n\n" +
                "### Before read_note\nNow read the note.\n\n" +
                "### Final answer\nSummarize for user.",
            formatted,
        )
    }

    @Test
    fun emptyTrace_fallsBackToFinalReasoning() {
        assertEquals("fallback", formatArchivedReasoningTrace(emptyList(), "fallback"))
        assertNull(formatArchivedReasoningTrace(emptyList(), null))
    }

    @Test
    fun persistableReasoningContent_storesFinalHopOnlyForChat() {
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
        val preview = response.persistableReasoningContent()!!
        assertTrue(preview.contains("Hop two"))
        assertTrue(preview.contains("2 provider thinking hops"))
        assertTrue(!preview.contains("Hop one"))
    }

    @Test
    fun recordReasoningHop_capsAtIngest() {
        val hops = mutableListOf<EidosReasoningHop>()
        val huge = "x".repeat(ReasoningPersistPolicy.MAX_PER_HOP_CHARS + 5_000)
        recordReasoningHop(
            hops = hops,
            response = EidosResponse(
                textResponse = "",
                assistantReasoningContent = huge,
                toolCalls = emptyList(),
            ),
            round = 0,
            phase = EidosRequestPhase.FULL,
        )
        assertEquals(1, hops.size)
        assertTrue(hops.single().reasoning.length <= ReasoningPersistPolicy.MAX_PER_HOP_CHARS)
    }
}
