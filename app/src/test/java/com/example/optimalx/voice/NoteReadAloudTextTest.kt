package com.example.optimalx.voice

import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalRichTextApi::class)
class NoteReadAloudTextTest {

    @Test
    fun sanitizeNoteContentForTts_stripsMarkdownSymbols() {
        val spoken = sanitizeNoteContentForTts(
            """
            ## Executive summary

            | Field | Value |
            |---|---|
            | **Status** | Active |
            """.trimIndent(),
        )

        assertTrue(spoken.contains("Executive summary"))
        assertTrue(spoken.contains("Status"))
        assertFalse(spoken.contains("|"))
        assertFalse(spoken.contains("**"))
        assertFalse(spoken.contains("##"))
    }

    @Test
    fun sanitizeNoteContentForTts_stripsHtmlTags() {
        val spoken = sanitizeNoteContentForTts("<p><strong>Hello</strong> world</p>")

        assertTrue(spoken.contains("Hello"))
        assertTrue(spoken.contains("world"))
        assertFalse(spoken.contains("<"))
    }

    @Test
    fun resolveNoteContentForReadAloud_prefersMarkdownSnapshot() {
        val state = RichTextState()
        state.setHtml("<p>ignored</p>")

        val resolved = resolveNoteContentForReadAloud(
            richTextState = state,
            viewSnapshot = "# Title\n\nBody",
        )

        assertTrue(resolved.startsWith("# Title"))
    }

    @Test
    fun shouldShowNoteMarkdownView_onlyInViewMode() {
        val state = RichTextState()
        state.setMarkdown("# Hello")
        assertTrue(shouldShowNoteMarkdownView(isViewMode = true, viewSnapshot = "# Hello", richTextState = state))
        assertFalse(shouldShowNoteMarkdownView(isViewMode = false, viewSnapshot = "# Hello", richTextState = state))
    }

    @Test
    fun resolveNoteMarkdownViewContent_normalizesLegacyHtmlSnapshot() {
        val state = RichTextState()
        val content = resolveNoteMarkdownViewContent("<p>## Summary</p>", state)
        assertTrue(content.contains("Summary"))
        assertFalse(content.contains("<p>"))
    }

    @Test
    fun captureNoteViewSnapshot_persistsMarkdownFromEditor() {
        val state = RichTextState()
        state.setMarkdown("**Bold** idea")
        val captured = captureNoteViewSnapshot(state, previousSnapshot = null)
        assertTrue(captured.contains("Bold"))
    }
}
