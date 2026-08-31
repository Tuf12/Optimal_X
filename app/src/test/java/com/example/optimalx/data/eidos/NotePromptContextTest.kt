package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePromptContextTest {

    @Test
    fun resolveTier_inline_for_small_body() {
        assertEquals(
            NotePromptContext.InjectTier.INLINE_FULL,
            NotePromptContext.resolveTier(NoteSummaryPolicy.INLINE_NOTE_MAX_CHARS),
        )
    }

    @Test
    fun resolveTier_large_above_inline_cap() {
        assertEquals(
            NotePromptContext.InjectTier.LARGE_RETRIEVE,
            NotePromptContext.resolveTier(NoteSummaryPolicy.INLINE_NOTE_MAX_CHARS + 1),
        )
    }

    @Test
    fun formatForPrompt_inlines_small_note_body() {
        val body = "Short note about grout."
        val output = NotePromptContext.formatForPrompt(
            note = Note(subfolderId = 1L, content = body),
            subfolderId = 1L,
            bodyMarkdown = body,
        )
        assertTrue(output.contains("full body inline"))
        assertTrue(output.contains(body))
    }

    @Test
    fun formatForPrompt_large_uses_memory_and_retrieval_not_body() {
        val body = "x".repeat(NoteSummaryPolicy.INLINE_NOTE_MAX_CHARS + 100)
        val summary = NoteSummaryCodec.format(
            memoryBullets = listOf("User prefers concise bullets."),
            contentDigest = "Digest of older content.",
        )!!
        val output = NotePromptContext.formatForPrompt(
            note = Note(
                subfolderId = 2L,
                content = body,
                summary = summary,
                summaryContentWatermark = 3_500,
            ),
            subfolderId = 2L,
            bodyMarkdown = body,
        )
        assertTrue(output.contains("folder memory only"))
        assertTrue(output.contains("User prefers concise bullets."))
        assertTrue(output.contains("Digest of older content."))
        assertTrue(output.contains("read_note"))
        assertTrue(output.contains("search_semantic"))
        assertFalse(output.contains("full body inline"))
        assertFalse(output.contains("Recent note body"))
        assertFalse(output.contains(body))
    }

    @Test
    fun formatForPrompt_blind_note_blocks_content() {
        val output = NotePromptContext.formatForPrompt(
            note = Note(subfolderId = 3L, content = "secret", aiBlind = true),
            subfolderId = 3L,
            bodyMarkdown = "secret",
        )
        assertEquals("Note: blind from Eidos (do not request content).", output)
    }

    @Test
    fun folderMemoryNudge_large_body_without_memory() {
        val nudge = NotePromptContext.folderMemoryNudge(
            note = Note(subfolderId = 4L, content = "x"),
            bodyLength = NoteSummaryPolicy.FOLD_TRIGGER_CHARS + 1,
        )
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("write_note_summary"))
    }

    @Test
    fun folderMemoryNudge_suppressed_when_memory_exists() {
        val summary = NoteSummaryCodec.format(listOf("Already stored."), "digest")!!
        val nudge = NotePromptContext.folderMemoryNudge(
            note = Note(subfolderId = 5L, summary = summary),
            bodyLength = NoteSummaryPolicy.FOLD_TRIGGER_CHARS + 1,
        )
        assertEquals(null, nudge)
    }
}
