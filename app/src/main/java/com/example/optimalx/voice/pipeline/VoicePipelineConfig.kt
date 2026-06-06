package com.example.optimalx.voice.pipeline

/**
 * Shared constants for voice-related components.
 *
 * In-app mic STT uses [com.example.optimalx.voice.SpeechToTextEngine]
 * (Google recognition service when available).
 */
object VoicePipelineConfig {
    const val SAMPLE_RATE_HZ = 16_000

    /** Throttle for partial STT text pushed to Compose state. */
    const val UI_UPDATE_THROTTLE_MS = 90L
}
