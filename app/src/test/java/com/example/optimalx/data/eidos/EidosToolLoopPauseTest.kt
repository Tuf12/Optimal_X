package com.example.optimalx.data.eidos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosToolLoopPauseTest {

    @Test
    fun message_includesRoundCountMarkerAndPendingTools() {
        val text = EidosToolLoopPause.message(
            priorText = "Working on the sketch pad.",
            toolRounds = 12,
            pendingToolNames = listOf("workshop_read_file", "search_semantic"),
        )
        assertTrue(text.contains("Working on the sketch pad."))
        assertTrue(text.contains("Paused after 12 tool steps"))
        assertTrue(text.contains("not finished"))
        assertTrue(text.contains("workshop_read_file"))
        assertTrue(text.contains("search_semantic"))
        assertTrue(text.endsWith(EidosToolLoopPause.MARKER))
    }

    @Test
    fun message_singularToolStep() {
        val text = EidosToolLoopPause.message(
            priorText = "",
            toolRounds = 1,
            pendingToolNames = emptyList(),
        )
        assertTrue(text.contains("Paused after 1 tool step "))
        assertFalse(text.contains("Was about to run"))
    }
}
