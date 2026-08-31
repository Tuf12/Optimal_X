package com.example.optimalx.data.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LitertLmMessageTextTest {

    @Test
    fun displayTextFromRaw_stripsTurnMarkup() {
        assertEquals(
            "Paris is the capital of France.",
            LitertLmMessageText.displayTextFromRaw(
                "<|turn>modelParis is the capital of France.",
            ),
        )
    }

    @Test
    fun displayTextFromRaw_normalizesEscapedNewlines() {
        assertEquals(
            "line1\nline2",
            LitertLmMessageText.displayTextFromRaw("line1\\nline2"),
        )
    }

    @Test
    fun displayTextFromRaw_returnsEmptyForToolJson() {
        assertEquals(
            "",
            LitertLmMessageText.displayTextFromRaw(
                """{"name":"search_semantic","arguments":{"query":"test"}}""",
            ),
        )
    }

    @Test
    fun accumulateDeltas_buildsFullReply() {
        // Mirrors LitertLmProvider: Message.toString() is a delta, not cumulative.
        val deltas = listOf("Paris ", "is the ", "capital of ", "France.")
        val accumulated = StringBuilder()
        var display = ""
        for (delta in deltas) {
            accumulated.append(delta)
            display = LitertLmMessageText.displayTextFromRaw(accumulated.toString())
        }
        assertEquals("Paris is the capital of France.", display)
    }

    @Test
    fun replaceInsteadOfAppend_leavesOnlyLastDelta() {
        // Documents the failure mode that produced 2–3 word replies.
        var replaced = ""
        for (delta in listOf("Paris ", "is the ", "capital of ", "France.")) {
            replaced = LitertLmMessageText.displayTextFromRaw(delta)
        }
        assertEquals("France.", replaced)
    }

    @Test
    fun isDegenerateAssistantText_detectsRepeatedThe() {
        val garbled = List(12) { "the" }.joinToString(" ")
        assertTrue(LitertLmMessageText.isDegenerateAssistantText(garbled))
        assertFalse(
            LitertLmMessageText.isDegenerateAssistantText(
                "The capital of France is Paris, and the river is the Seine.",
            ),
        )
    }

    @Test
    fun isLikelyToolJson_detectsFunctionCallShape() {
        assertTrue(
            LitertLmMessageText.isLikelyToolJson(
                """{"name":"search_semantic","arguments":{"query":"test"}}""",
            ),
        )
        assertFalse(LitertLmMessageText.isLikelyToolJson("Paris is the capital of France."))
        assertFalse(LitertLmMessageText.isLikelyToolJson("{\"greeting\":\"hi\"}"))
    }
}
