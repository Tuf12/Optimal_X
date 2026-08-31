package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSummaryChunksCodecTest {

    @Test
    fun encode_decode_round_trip() {
        val chunks = listOf(
            ContentSummaryChunk(anchor = "Part 1", text = "First section summary."),
            ContentSummaryChunk(anchor = "Part 2", text = "Second section summary."),
        )
        val encoded = ContentSummaryChunksCodec.encode(chunks)
        requireNotNull(encoded)
        assertEquals(chunks, ContentSummaryChunksCodec.decode(encoded))
    }

    @Test
    fun decode_legacy_json_array() {
        val json =
            """[{"anchor":"Part 1","text":"First section summary."},""" +
                """{"anchor":"Part 2","text":"Second section summary."}]"""
        val decoded = ContentSummaryChunksCodec.decode(json)
        assertEquals(2, decoded.size)
        assertEquals("Part 1", decoded[0].anchor)
    }

    @Test
    fun decode_blank_returns_empty() {
        assertTrue(ContentSummaryChunksCodec.decode(null).isEmpty())
        assertTrue(ContentSummaryChunksCodec.decode("").isEmpty())
    }
}
