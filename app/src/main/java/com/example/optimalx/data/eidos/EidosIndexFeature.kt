package com.example.optimalx.data.eidos

/**
 * Eidos Index (tag_hint_lines, materializer, sync, index tools, index UI) is **ON HOLD**.
 *
 * Retrieval uses semantic vector embeddings via [search_semantic] instead of the
 * Tag & Hint routing catalog. Implementation remains in the codebase for a possible
 * future revival (e.g. training-app taxonomy); set [isActive] to true to re-enable.
 */
object EidosIndexFeature {

    /** Shipping default: index disabled. */
    const val ENABLED = false

    /** Test-only override; null uses [ENABLED]. */
    @Volatile
    var enabledOverride: Boolean? = null

    val isActive: Boolean
        get() = enabledOverride ?: ENABLED

    const val ON_HOLD_MESSAGE =
        "Eidos Index is on hold; use search_semantic for retrieval instead."
}
