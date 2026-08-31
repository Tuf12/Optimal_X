package com.example.optimalx.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioWavCodecTest {

    @Test
    fun alignToFrame_clearsOddByte() {
        assertEquals(0, AudioWavCodec.alignToFrame(-3))
        assertEquals(0, AudioWavCodec.alignToFrame(0))
        assertEquals(0, AudioWavCodec.alignToFrame(1))
        assertEquals(2, AudioWavCodec.alignToFrame(2))
        assertEquals(2, AudioWavCodec.alignToFrame(3))
        assertEquals(100, AudioWavCodec.alignToFrame(101))
    }

    @Test
    fun pcmByteLength_matchesSampleRate() {
        // 20s * 16000 * 2 = 640_000
        assertEquals(640_000, AudioWavCodec.pcmByteLength(20f))
        assertEquals(16_000, AudioWavCodec.pcmByteLength(0.5f))
    }

    @Test
    fun encodeWav_writesRiffHeaderAndPcm() {
        val pcm = ByteArray(8) { it.toByte() }
        val wav = AudioWavCodec.encodeWav(pcm)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun sliceWindow_overlapMath() {
        val slice = AudioWavCodec.pcmByteLength(20f)
        val overlap = AudioWavCodec.pcmByteLength(0.5f)
        var nextStart = 0
        val firstStart = (nextStart - overlap).coerceAtLeast(0)
        val firstEnd = nextStart + slice
        assertEquals(0, firstStart)
        assertEquals(slice, firstEnd)
        nextStart = firstEnd
        val secondStart = (nextStart - overlap).coerceAtLeast(0)
        val secondEnd = nextStart + slice
        assertEquals(slice - overlap, secondStart)
        assertEquals(slice * 2, secondEnd)
        assertTrue(secondEnd - secondStart > slice)
    }
}
