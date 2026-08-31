package com.example.optimalx.data.litert

enum class LitertLmWarmState {
    /** Engine not loaded and warm pool not required, or released after settings/memory pressure. */
    Idle,
    /** `initialize()` in progress. */
    Loading,
    /** Engine loaded and ready for chat, scribe, or vision. */
    Ready,
    /** Last prepare failed (missing model, native error, etc.). */
    Error,
}
