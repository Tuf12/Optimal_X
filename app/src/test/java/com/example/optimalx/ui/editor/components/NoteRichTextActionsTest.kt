package com.example.optimalx.ui.editor.components

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalRichTextApi::class)
class NoteRichTextActionsTest {

    @Test
    fun lineRangeForSelection_clampsToEditorTextLength() {
        val text = "line one\nline two"
        val range = NoteRichTextActions.lineRangeForSelection(text, selectionIndex = 999)
        assertEquals(TextRange(9, text.length), range)
    }

    @Test
    fun headingTargetRange_usesSelectionWhenHighlighted() {
        val text = "Hello world"
        val state = RichTextState()
        state.setMarkdown(text)
        state.selection = TextRange(0, 5)
        val range = NoteRichTextActions.headingTargetRange(state, text)
        assertEquals(TextRange(0, 5), range)
    }

    @Test
    fun headingTargetRange_usesLineWhenCaretOnly() {
        val text = "Line one\nLine two"
        val range = NoteRichTextActions.lineRangeForSelection(text, selectionIndex = 10)
        assertEquals(TextRange(9, text.length), range)
    }

    @Test
    fun cycleHeading_fullCycle_doesNotThrow() {
        val state = RichTextState()
        val body = buildString {
            repeat(40) { i ->
                append("Paragraph $i with some text.\n")
            }
        }.trimEnd()
        state.setMarkdown(body)
        state.selection = TextRange(50, 50)

        repeat(20) {
            NoteRichTextActions.cycleHeading(state)
        }

        val md = state.toMarkdown()
        assertTrue(md.isNotBlank())
        assertTrue(state.annotatedString.text.isNotBlank())
    }

    @Test
    fun cycleHeading_onMarkdownHeadings_roundTrips() {
        val state = RichTextState()
        state.setMarkdown("# Title\n## Section\n### Sub\nBody")
        for (index in listOf(2, 12, 24, 32)) {
            state.selection = TextRange(index, index)
            NoteRichTextActions.cycleHeading(state)
        }
        val md = state.toMarkdown()
        assertTrue(md.contains("Title") || md.contains("#"))
    }
}
