package com.example.optimalx.ui.components

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalRichTextApi::class)
class NoteContentCodecPasteTest {

    @Test
    fun pasteMarkdown_intoEmptyNote_rendersHeadingInsteadOfRawAtx() {
        val state = RichTextState()
        NoteContentCodec.pasteIntoRichText(state, "# Hello\n\n**bold** body")
        val visual = state.annotatedString.text
        assertTrue("Expected heading text, got [$visual]", visual.contains("Hello"))
        assertTrue("Expected bold body, got [$visual]", visual.contains("bold"))
        assertFalse("Pasted markdown should render, not show raw ATX: [$visual]", visual.contains("# Hello"))
        val md = NoteContentCodec.persistFromRichText(state)
        assertTrue("Persisted markdown should keep heading: [$md]", md.contains("# Hello") || md.contains("Hello"))
    }

    @Test
    fun pasteMarkdown_overSelectAll_replacesAndRenders() {
        val state = RichTextState()
        state.setMarkdown("old note that should disappear")
        state.selection = TextRange(0, state.annotatedString.text.length)
        val incoming = buildString {
            append("# New title\n\n")
            repeat(40) { index -> append("- item $index\n") }
        }
        NoteContentCodec.pasteIntoRichText(state, incoming)
        val visual = state.annotatedString.text
        assertTrue(visual.contains("New title"))
        assertTrue(visual.contains("item 0"))
        assertFalse("Expected rendered heading, got [$visual]", visual.contains("# New title"))
        assertFalse(visual.contains("old note that should disappear"))
    }

    @Test
    fun clipboardToMarkdown_prefersHtmlFormatting() {
        val md = NoteContentCodec.clipboardToMarkdown(
            plain = "Hello world",
            html = "<p>Hello <strong>world</strong></p>",
        )
        assertTrue(md.contains("Hello"))
        assertTrue(md.contains("world"))
        assertFalse(md.noteTextLooksLikeHtml())
    }

    @Test
    fun clipboardToMarkdown_keepsPlainMarkdownWhenNoHtml() {
        val source = "# Title\n\n**bold** body"
        val md = NoteContentCodec.clipboardToMarkdown(plain = source, html = null)
        assertEquals(source, md)
    }

    @Test
    fun pasteMarkdown_atCaret_keepsSurroundingText() {
        val state = RichTextState()
        state.setMarkdown("alpha omega")
        val visual = state.annotatedString.text
        val idx = visual.indexOf("omega").coerceAtLeast(0)
        state.selection = TextRange(idx)
        NoteContentCodec.pasteIntoRichText(state, "**mid** ")
        val after = state.annotatedString.text
        assertTrue(after.contains("alpha"))
        assertTrue(after.contains("omega"))
        assertTrue(after.contains("mid"))
    }
}
