package com.example.optimalx.ui.components

import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalRichTextApi::class)
class MarkdownRichTextTest {

    private fun markdownToHtml(markdown: String): String {
        val state = RichTextState()
        state.setMarkdown(markdown)
        return state.toHtml()
    }

    @Test
    fun inlineCode_preservesCommandText() {
        val html = markdownToHtml("Run `adb shell pm list packages` now.")
        assertTrue(html.contains("adb shell pm list packages"))
    }

    @Test
    fun fencedCodeBlock_preservesCommandText() {
        val markdown = """
            Run this command:

            ```bash
            adb shell pm list packages
            ```
        """.trimIndent()
        val html = chatMarkdownToDisplayHtml(markdown)
        assertTrue(
            "Expected fenced code content in HTML but got: $html",
            html.contains("adb shell pm list packages"),
        )
    }

    @Test
    fun fencedCodeBlock_doesNotCollapseToPlaceholder() {
        val markdown = "```\ngit status\n```"
        val html = chatMarkdownToDisplayHtml(markdown)
        assertTrue("Expected git status in HTML but got: $html", html.contains("git status"))
    }

    @Test
    fun richeditorMarkdownParser_dropsFencedCodeBlocks() {
        val markdown = "```\ngit status\n```"
        val html = markdownToHtml(markdown)
        assertFalse("richeditor drops fenced code (documented limitation)", html.contains("git status"))
    }

    @Test
    fun htmlPreCode_preservesCommandText() {
        val htmlInput = "<p>Run this:</p><pre><code>adb shell pm list packages</code></pre>"
        val state = RichTextState()
        state.setHtml(htmlInput)
        val html = state.toHtml()
        assertTrue("Expected command in round-trip HTML but got: $html", html.contains("adb shell pm list packages"))
    }

    @Test
    fun chatMarkdownToDisplayHtml_preservesFencedCodeBlock() {
        val markdown = """
            Run this command:

            ```bash
            adb shell pm list packages
            ```
        """.trimIndent()
        val html = chatMarkdownToDisplayHtml(markdown)
        assertTrue("Expected fenced code in display HTML but got: $html", html.contains("adb shell pm list packages"))
        assertTrue("Expected pre/code wrapper but got: $html", html.contains("<pre") && html.contains("<code"))
    }

    @Test
    fun escapeHtmlText_escapesSpecialCharacters() {
        assertTrue(escapeHtmlText("a && b < c").contains("&amp;"))
        assertTrue(escapeHtmlText("a && b < c").contains("&lt;"))
    }

    @Test
    fun plainTextToFallbackHtml_escapesAndPreservesLines() {
        val html = plainTextToFallbackHtml("line one\n\nline two <tag>")
        assertTrue(html.contains("line one"))
        assertTrue(html.contains("line two &lt;tag&gt;"))
        assertTrue(html.contains("<br>"))
    }

    @Test
    fun chatMarkdownToDisplayHtml_neverThrowsOnLongMalformedInput() {
        val markdown = buildString {
            append("# Notes\n\n")
            append("- bullet\n\n")
            append("x".repeat(1450))
            append("\n**unclosed")
        }
        val html = chatMarkdownToDisplayHtml(markdown)
        assertTrue(html.isNotBlank())
    }
}
