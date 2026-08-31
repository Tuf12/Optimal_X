package com.example.optimalx.voice

import com.example.optimalx.voice.pipeline.VoicePipelineConfig
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mono PCM16 LE helpers for Whisper / Gemma scribe WAV containers.
 */
object AudioWavCodec {
    const val BYTES_PER_SAMPLE: Int = 2

    fun bytesPerSecond(sampleRateHz: Int = VoicePipelineConfig.SAMPLE_RATE_HZ): Int =
        sampleRateHz * BYTES_PER_SAMPLE

    fun pcmByteLength(seconds: Float, sampleRateHz: Int = VoicePipelineConfig.SAMPLE_RATE_HZ): Int {
        val raw = (seconds * bytesPerSecond(sampleRateHz)).toInt()
        return alignToFrame(raw)
    }

    /** PCM16 frames are 2 bytes — keep indices even. */
    fun alignToFrame(byteIndex: Int): Int =
        if (byteIndex <= 0) 0 else byteIndex and 0x7FFFFFFE

    /** Mono 16-bit PCM LE → RIFF WAVE. */
    fun encodeWav(
        pcm: ByteArray,
        sampleRateHz: Int = VoicePipelineConfig.SAMPLE_RATE_HZ,
    ): ByteArray {
        val totalDataLen = pcm.size + 36
        val byteRate = sampleRateHz * BYTES_PER_SAMPLE
        val header = ByteArrayOutputStream(44)
        header.write("RIFF".toByteArray())
        header.write(intToLittleEndian(totalDataLen))
        header.write("WAVE".toByteArray())
        header.write("fmt ".toByteArray())
        header.write(intToLittleEndian(16))
        header.write(shortToLittleEndian(1)) // PCM
        header.write(shortToLittleEndian(1)) // mono
        header.write(intToLittleEndian(sampleRateHz))
        header.write(intToLittleEndian(byteRate))
        header.write(shortToLittleEndian(BYTES_PER_SAMPLE)) // block align
        header.write(shortToLittleEndian(16)) // bits per sample
        header.write("data".toByteArray())
        header.write(intToLittleEndian(pcm.size))
        return header.toByteArray() + pcm
    }

    private fun intToLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortToLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
}
