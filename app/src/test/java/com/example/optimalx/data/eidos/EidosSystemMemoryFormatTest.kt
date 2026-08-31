package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosSystemMemoryFormatTest {

    @Test
    fun parseEntries_splitsTimestampedChunks() {
        val content = """
            [2026-07-08 10:00:00] First entry

            [2026-07-08 11:00:00] Second entry
        """.trimIndent()

        val entries = EidosSystemMemoryFormat.parseEntries(
            subfolderId = 1L,
            subfolderName = "2026-07-08",
            noteContent = content,
            fallbackUpdatedAt = 0L,
        )

        assertEquals(2, entries.size)
        assertEquals("First entry", entries[0].content)
        assertEquals("Second entry", entries[1].content)
        assertEquals(0, entries[0].chunkIndex)
        assertEquals(1, entries[1].chunkIndex)
    }

    @Test
    fun joinChunks_roundTripsAfterRemoval() {
        val chunks = listOf(
            "[2026-07-08 10:00:00] Keep",
            "[2026-07-08 11:00:00] Remove",
        )
        val kept = chunks.filterIndexed { index, _ -> index != 1 }
        val joined = EidosSystemMemoryFormat.joinChunks(kept)
        assertTrue(joined.contains("Keep"))
        assertTrue(!joined.contains("Remove"))
    }
}
