package com.example.optimalx.data.eidos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyMemoryContextTest {

    @Test
    fun formatPromptBlock_emptyContent_showsEmptyMarker() {
        val block = DailyMemoryContext.formatPromptBlock(
            dateKey = "2026-06-22",
            content = "",
            aiBlinded = false,
        )
        assertTrue(block.contains("Daily Memory (2026-06-22):"))
        assertTrue(block.contains("(empty)"))
        assertTrue(block.contains("write_daily_memory"))
    }

    @Test
    fun formatPromptBlock_aiBlinded_omitsContent() {
        val block = DailyMemoryContext.formatPromptBlock(
            dateKey = "2026-06-22",
            content = "secret decision",
            aiBlinded = true,
        )
        assertTrue(block.contains("AI access disabled"))
        assertFalse(block.contains("secret decision"))
    }

    @Test
    fun formatPromptBlock_truncatesLongContent() {
        val long = "x".repeat(DailyMemoryContext.MAX_CONTENT_CHARS + 500)
        val block = DailyMemoryContext.formatPromptBlock(
            dateKey = "2026-06-22",
            content = long,
            aiBlinded = false,
        )
        assertTrue(block.contains("truncated"))
        assertFalse(block.contains("x".repeat(DailyMemoryContext.MAX_CONTENT_CHARS + 100)))
    }
}
