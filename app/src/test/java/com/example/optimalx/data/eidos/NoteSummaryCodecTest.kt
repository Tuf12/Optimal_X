package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSummaryCodecTest {

    @Test
    fun parse_empty_returns_empty_sections() {
        assertTrue(NoteSummaryCodec.parse(null).isEmpty)
        assertTrue(NoteSummaryCodec.parse("").isEmpty)
    }

    @Test
    fun parse_legacy_plain_summary_goes_to_content() {
        val sections = NoteSummaryCodec.parse("Old overview of the project.")
        assertTrue(sections.memoryBullets.isEmpty())
        assertEquals("Old overview of the project.", sections.contentDigest)
    }

    @Test
    fun format_and_parse_round_trip() {
        val formatted = NoteSummaryCodec.format(
            memoryBullets = listOf("User prefers short sentences.", "Formal tone for clients."),
            contentDigest = "Intro covers goals and timeline.",
        )!!
        val parsed = NoteSummaryCodec.parse(formatted)
        assertEquals(
            listOf("User prefers short sentences.", "Formal tone for clients."),
            parsed.memoryBullets,
        )
        assertEquals("Intro covers goals and timeline.", parsed.contentDigest)
        assertTrue(formatted.contains(NoteSummaryPolicy.MEMORY_HEADER))
        assertTrue(formatted.contains(NoteSummaryPolicy.CONTENT_HEADER))
    }

    @Test
    fun migrateLegacyStoredSummary_wraps_plain_summary_in_content() {
        val migrated = NoteSummaryCodec.migrateLegacyStoredSummary(
            summary = "Legacy single summary.",
            summaryChunksJson = null,
        )
        val parsed = NoteSummaryCodec.parse(migrated)
        assertTrue(parsed.memoryBullets.isEmpty())
        assertEquals("Legacy single summary.", parsed.contentDigest)
    }

    @Test
    fun format_preserves_multiline_content_digest() {
        val digest = "Overview line.\n\n[Part 1]\nFirst section."
        val formatted = NoteSummaryCodec.format(emptyList(), digest)!!
        val parsed = NoteSummaryCodec.parse(formatted)
        assertEquals(digest, parsed.contentDigest)
    }

    @Test
    fun appendMemoryBullet_enforces_duplicate_and_cap() {
        val base = NoteSummarySections()
        val first = NoteSummaryCodec.appendMemoryBullet(base, "Prefer bullets.")
        assertTrue(first is NoteSummaryEditResult.Success)

        val dup = NoteSummaryCodec.appendMemoryBullet(
            (first as NoteSummaryEditResult.Success).sections,
            "Prefer bullets.",
        )
        assertTrue(dup is NoteSummaryEditResult.Failure)
    }

    @Test
    fun validateAndFormat_rejects_memory_over_cap() {
        val bullets = (1..NoteSummaryPolicy.MAX_MEMORY_BULLETS + 1).map { "Bullet $it" }
        val result = NoteSummaryCodec.validateAndFormat(
            NoteSummarySections(memoryBullets = bullets, contentDigest = ""),
        )
        assertTrue(result is NoteSummaryEditResult.Failure)
    }

    @Test
    fun validateAndFormat_accepts_valid_sections() {
        val result = NoteSummaryCodec.validateAndFormat(
            NoteSummarySections(
                memoryBullets = listOf("User prefers bullets."),
                contentDigest = "Digest line.",
            ),
        )
        assertTrue(result is NoteSummaryEditResult.Success)
    }

    @Test
    fun replace_and_remove_memory_bullet_by_index() {
        var sections = NoteSummarySections(
            memoryBullets = listOf("Alpha", "Beta", "Gamma"),
        )
        val replaced = NoteSummaryCodec.replaceMemoryBullet(sections, match = "2", item = "Bravo")
        assertTrue(replaced is NoteSummaryEditResult.Success)
        sections = (replaced as NoteSummaryEditResult.Success).sections
        assertEquals(listOf("Alpha", "Bravo", "Gamma"), sections.memoryBullets)

        val removed = NoteSummaryCodec.removeMemoryBullet(sections, match = "Alpha")
        assertTrue(removed is NoteSummaryEditResult.Success)
        assertEquals(listOf("Bravo", "Gamma"), (removed as NoteSummaryEditResult.Success).sections.memoryBullets)
    }

    @Test
    fun withContentDigest_rejects_over_cap() {
        val huge = "x".repeat(NoteSummaryPolicy.MAX_CONTENT_DIGEST_CHARS + 1)
        val result = NoteSummaryCodec.withContentDigest(NoteSummarySections(), huge)
        assertTrue(result is NoteSummaryEditResult.Failure)
    }

    @Test
    fun format_empty_sections_returns_null() {
        assertNull(NoteSummaryCodec.format(NoteSummarySections()))
    }
}
