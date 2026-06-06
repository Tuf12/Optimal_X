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
        val json = ContentSummaryChunksCodec.encode(chunks)
        requireNotNull(json)
        val decoded = ContentSummaryChunksCodec.decode(json)
        assertEquals(chunks, decoded)
    }

    @Test
    fun decode_blank_returns_empty() {
        assertTrue(ContentSummaryChunksCodec.decode(null).isEmpty())
        assertTrue(ContentSummaryChunksCodec.decode("").isEmpty())
    }
}
