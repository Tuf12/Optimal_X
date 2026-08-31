package com.example.optimalx.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTextFormatTest {

    @Test
    fun noteTextLooksLikeHtml_detectsRichTextTags() {
        assertTrue("<p>Hello</p>".noteTextLooksLikeHtml())
        assertFalse("# Title\n\nBody".noteTextLooksLikeHtml())
    }

    @Test
    fun noteTextLooksLikeMarkdown_detectsHeadersAndTables() {
        val md = """
            # Prompt scope router plan

            | Field | Value |
            |---|---|
        """.trimIndent()
        assertTrue(md.noteTextLooksLikeMarkdown())
        assertTrue("1. First\n2. Second".noteTextLooksLikeMarkdown())
        assertFalse("<p><strong>Title</strong></p>".noteTextLooksLikeMarkdown())
    }

    @Test
    fun noteTextHasRenderableMarkdown_detectsMarkdownWrappedInHtml() {
        val html = "<p># Prompt scope router plan</p><p>| Field | Value |</p>"
        assertTrue(html.noteTextHasRenderableMarkdown())
        assertFalse("<p><strong>Title</strong></p>".noteTextHasRenderableMarkdown())
    }
}
