package com.example.optimalx.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextSanitizerTest {

    @Test
    fun stripsSourceUrlsAndNoisyWebSymbols() {
        val spoken = stripMarkdownForTts(
            """
            **Short answer:** The update is available (preview).

            Sources:
            - Docs: https://example.com/path/(preview)<id>*?q=1
            - Blog: https://example.com/blog/post
            """.trimIndent()
        )

        assertTrue(spoken.contains("Short answer: The update is available preview."))
        assertFalse(spoken.contains("https://"))
        assertFalse(spoken.contains("Sources:"))
        assertFalse(spoken.contains("<"))
        assertFalse(spoken.contains("*"))
        assertFalse(spoken.contains("/"))
    }

    @Test
    fun keepsMarkdownLinkTextWithoutReadingUrl() {
        val spoken = stripMarkdownForTts("Read [the provider docs](https://example.com/a/b) before enabling it.")

        assertTrue(spoken.contains("Read the provider docs before enabling it."))
        assertFalse(spoken.contains("example.com"))
        assertFalse(spoken.contains("("))
    }
}
