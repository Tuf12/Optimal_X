package com.example.optimalx.ui.editor.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownNoteEditorStateTest {

    @Test
    fun eidosMarkdown_survivesLoadWithoutReencode() {
        val source = """
            # Grocery plan

            - milk
            - **eggs**

            ```
            git status
            ```

            See [link](https://example.com)
        """.trimIndent()
        val state = MarkdownNoteEditorState()
        state.load(source)
        assertEquals(source, state.markdown)
    }

    @Test
    fun pasteMarkdown_insertsSourceNotHtml() {
        val state = MarkdownNoteEditorState()
        state.load("alpha")
        state.pasteClipboard("# Title\n\n**bold**", html = null)
        assertTrue(state.markdown.contains("# Title"))
        assertTrue(state.markdown.contains("**bold**"))
        assertTrue(state.markdown.contains("alpha"))
    }
}
