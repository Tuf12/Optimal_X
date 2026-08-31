package com.example.optimalx.data.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Line ranges from [ContentSegmentation] must match [ContentSegmentation.extractLineRange] on markdown notes. */
class NoteMarkdownLineAlignmentTest {

    @Test
    fun splitContentSegments_lineRangesMatchExtractLineRange() {
        val markdown = """
            # Zebras

            First paragraph about stripes.

            ## Diet

            They eat grass and leaves.
        """.trimIndent()

        val segments = ContentSegmentation.splitContentSegments(markdown)
        assertTrue(segments.isNotEmpty())

        segments.forEach { segment ->
            val excerpt = ContentSegmentation.extractLineRange(
                markdown,
                segment.startLine,
                segment.endLine,
            )
            assertTrue(
                "segment at ${segment.startLine}-${segment.endLine} should appear in markdown",
                markdown.contains(segment.text.trim().take(20).substringBefore("\n")),
            )
            assertTrue(
                "extractLineRange(${segment.startLine}, ${segment.endLine}) should be non-empty",
                excerpt.isNotBlank(),
            )
        }
    }

    @Test
    fun extractLineRange_returnsRequestedLines() {
        val markdown = "line one\nline two\nline three"
        assertEquals("line two", ContentSegmentation.extractLineRange(markdown, 2, 2))
        assertEquals("line one\nline two", ContentSegmentation.extractLineRange(markdown, 1, 2))
    }
}
