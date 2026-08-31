package com.example.optimalx.data.eidos

/**
 * Temporary kill-switches for Eidos system surfaces that are not ready to ship.
 * Flip to true when journal write quality and nightly rollover are ready.
 */
object EidosSystemFeatureFlags {
    /** Eidos Journal tools, prefetch corpus, and semantic indexing for journal notes. */
    const val JOURNAL_ENABLED = false

    /** Nightly WorkManager rollover and manual force rollover in Settings. */
    const val MEMORY_ROLLOVER_ENABLED = false
}
