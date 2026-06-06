package com.example.optimalx.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [LinkAuthToken]. The encoder is hand-rolled (no Android types)
 * specifically so these tests can run without an emulator.
 */
class LinkAuthTokenTest {

    @Test
    fun generate_defaultLength_returnsBase64Url() {
        val token = LinkAuthToken.generate()
        // 24 bytes → ceil(24*8 / 6) = 32 base64 chars, no padding.
        assertEquals(32, token.length)
        assertTrue(
            "token must only contain URL-safe base64 alphabet, got: $token",
            token.all { it.isLetterOrDigit() || it == '-' || it == '_' },
        )
    }

    @Test
    fun generate_customLength_scalesProportionally() {
        // 9 raw bytes → 12 base64 chars (9 * 8 = 72 bits → 12 * 6 = 72).
        assertEquals(12, LinkAuthToken.generate(byteLength = 9).length)
        // 32 raw bytes → 43 chars (32 * 8 / 6 = 42.66… → ceil = 43, no padding).
        assertEquals(43, LinkAuthToken.generate(byteLength = 32).length)
    }

    @Test
    fun generate_isUnpredictable() {
        // Two tokens generated back-to-back must differ. The chance of collision
        // at 192 bits of entropy is ~1 in 2^192 — well past test flakiness range.
        val a = LinkAuthToken.generate()
        val b = LinkAuthToken.generate()
        assertNotEquals(a, b)
    }

    @Test
    fun generate_hasFullDistinctiveness_acrossManyDraws() {
        // 1000 draws, expect zero duplicates. Validates the random source is
        // actually wired (not seeded with a constant).
        val seen = HashSet<String>()
        repeat(1_000) {
            assertTrue("duplicate token found", seen.add(LinkAuthToken.generate()))
        }
        assertEquals(1_000, seen.size)
    }

    @Test
    fun generate_rejectsZeroOrNegativeByteLength() {
        assertThrows(IllegalArgumentException::class.java) {
            LinkAuthToken.generate(byteLength = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinkAuthToken.generate(byteLength = -1)
        }
    }
}
