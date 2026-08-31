package com.example.optimalx.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteExportTest {

    @Test
    fun sanitizeFileName_pdf() {
        assertTrue(NoteExport.sanitizeFileName("My Note", NoteExportFormat.PDF).endsWith(".pdf"))
    }

    @Test
    fun sanitizeFileName_markdown() {
        assertTrue(NoteExport.sanitizeFileName("note", NoteExportFormat.MARKDOWN).endsWith(".md"))
    }

    @Test
    fun buildRenderedHtmlDocument_includesHeadingMarkup() {
        val html = NoteExport.buildRenderedHtmlDocument("Test", "# Title\n\n**bold**")
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertFalse("Expected rendered heading, got raw markdown", html.contains("# Title"))
        assertTrue("Expected bold markup in $html", html.contains("<strong>") || html.contains("<b>"))
    }
}
