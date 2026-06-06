package com.example.optimalx.data.revision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentDiffTest {

    @Test
    fun unifiedDiff_identical_returnsEmpty() {
        val a = "line one\nline two\nline three\n"
        assertEquals("", ContentDiff.unifiedDiff(a, a))
    }

    @Test
    fun unifiedDiff_singleLineChange_hasOneHunk() {
        val before = "a\nb\nc\nd\ne\n"
        val after = "a\nb\nC\nd\ne\n"
        val diff = ContentDiff.unifiedDiff(before, after, contextLines = 2)
        assertTrue("diff should start with hunk header but was: $diff", diff.startsWith("@@"))
        assertTrue(diff.contains("-c"))
        assertTrue(diff.contains("+C"))
        assertEquals(1, diff.lines().count { it.startsWith("@@") })
    }

    @Test
    fun applyUnifiedDiff_roundtrips_singleChange() {
        val before = "alpha\nbeta\ngamma\ndelta\nepsilon\n"
        val after = "alpha\nBETA\ngamma\ndelta\nepsilon\n"
        val diff = ContentDiff.unifiedDiff(before, after)
        val applied = ContentDiff.applyUnifiedDiff(before, diff)
        assertEquals(after, applied)
    }

    @Test
    fun applyUnifiedDiff_roundtrips_pureInsertion() {
        val before = "alpha\nbeta\n"
        val after = "alpha\nbeta\ngamma\ndelta\n"
        val diff = ContentDiff.unifiedDiff(before, after)
        val applied = ContentDiff.applyUnifiedDiff(before, diff)
        assertEquals(after, applied)
    }

    @Test
    fun applyUnifiedDiff_roundtrips_pureDeletion() {
        val before = "alpha\nbeta\ngamma\ndelta\n"
        val after = "alpha\ndelta\n"
        val diff = ContentDiff.unifiedDiff(before, after)
        val applied = ContentDiff.applyUnifiedDiff(before, diff)
        assertEquals(after, applied)
    }

    @Test
    fun applyUnifiedDiff_roundtrips_multiHunk() {
        val before = buildString {
            (1..30).forEach { appendLine("line $it") }
        }
        val after = buildString {
            (1..30).forEach { i ->
                when (i) {
                    3 -> appendLine("line 3 modified")
                    20 -> appendLine("line 20 modified")
                    27 -> {
                        appendLine("line 27 modified")
                        appendLine("inserted between 27 and 28")
                    }
                    else -> appendLine("line $i")
                }
            }
        }
        val diff = ContentDiff.unifiedDiff(before, after)
        // Two distant edit clusters should produce two hunks (the third edit at 27
        // overlaps the second cluster's context window in default config).
        assertTrue("diff should have at least two hunks: $diff", diff.lines().count { it.startsWith("@@") } >= 2)
        val applied = ContentDiff.applyUnifiedDiff(before, diff)
        assertEquals(after, applied)
    }

    @Test
    fun applyUnifiedDiff_roundtrips_emptyToContent() {
        val before = ""
        val after = "first\nsecond\n"
        val diff = ContentDiff.unifiedDiff(before, after)
        assertTrue(diff.startsWith("@@"))
        assertEquals(after, ContentDiff.applyUnifiedDiff(before, diff))
    }

    @Test
    fun applyUnifiedDiff_roundtrips_contentToEmpty() {
        val before = "first\nsecond\n"
        val after = ""
        val diff = ContentDiff.unifiedDiff(before, after)
        assertEquals(after, ContentDiff.applyUnifiedDiff(before, diff))
    }

    @Test
    fun applyUnifiedDiff_preservesTrailingNewlineState() {
        val before = "a\nb\nc"
        val after = "a\nB\nc"
        val diff = ContentDiff.unifiedDiff(before, after)
        assertEquals(after, ContentDiff.applyUnifiedDiff(before, diff))
    }

    @Test
    fun applyUnifiedDiff_failsOnContextMismatch() {
        val before = "alpha\nbeta\ngamma\n"
        val tampered = "ALPHA\nbeta\ngamma\n"
        val diff = ContentDiff.unifiedDiff(before, "alpha\nbeta\nGAMMA\n")
        var threw = false
        try {
            ContentDiff.applyUnifiedDiff(tampered, diff)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected context mismatch to throw", threw)
    }

    @Test
    fun unifiedDiff_largeInput_fallsBackToWholeReplace() {
        val before = buildString {
            (1..(ContentDiff.MAX_DIFF_LINES + 1)).forEach { appendLine("a$it") }
        }
        val after = buildString {
            (1..(ContentDiff.MAX_DIFF_LINES + 1)).forEach { appendLine("b$it") }
        }
        val diff = ContentDiff.unifiedDiff(before, after)
        // Fallback hunk: every old line shows as deletion, every new line as insertion.
        assertEquals(1, diff.lines().count { it.startsWith("@@") })
        assertTrue(diff.lines().any { it.startsWith("-a1") })
        assertTrue(diff.lines().any { it.startsWith("+b1") })
    }

    @Test
    fun sha256Hex_isStableAndLowercase() {
        val hash = ContentDiff.sha256Hex("hello world")
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            hash,
        )
        assertEquals(hash, hash.lowercase())
    }

    @Test
    fun sha256Hex_differentForDifferentContent() {
        assertNotEquals(
            ContentDiff.sha256Hex("alpha"),
            ContentDiff.sha256Hex("beta"),
        )
    }

    @Test
    fun snippetAround_findsNeedleAndIncludesContext() {
        val content = buildString {
            (1..20).forEach { appendLine("line $it") }
        }
        val snippet = ContentDiff.snippetAround(content, "line 10", contextLines = 2)
        assertEquals(8, snippet.startLine)
        assertEquals(12, snippet.endLine)
        assertTrue(snippet.text.contains("line 10"))
        assertTrue(snippet.text.contains("line 8"))
        assertTrue(snippet.text.contains("line 12"))
        assertFalse(snippet.text.contains("line 7"))
        assertFalse(snippet.text.contains("line 13"))
    }

    @Test
    fun snippetAround_formatsWithLineNumbers() {
        val content = "alpha\nbeta\ngamma\ndelta\nepsilon\n"
        val snippet = ContentDiff.snippetAround(content, "gamma", contextLines = 1)
        val formatted = snippet.formatWithLineNumbers()
        assertTrue(formatted.contains("2 | beta"))
        assertTrue(formatted.contains("3 | gamma"))
        assertTrue(formatted.contains("4 | delta"))
    }

    @Test
    fun snippetAround_missingNeedle_fallsBackToStart() {
        val content = "alpha\nbeta\ngamma\n"
        val snippet = ContentDiff.snippetAround(content, "zeta")
        assertEquals(1, snippet.startLine)
    }
}
