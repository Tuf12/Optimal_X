package com.example.optimalx.data.eidos.prefetch

object EidosRetrievalQuery {

    private val greetingPatterns = listOf(
        "hi", "hello", "hey", "thanks", "thank you", "ok", "okay", "yo", "sup",
    )

    private val probePatterns = listOf(
        "test", "testing", "ping",
    )

    private const val MIN_SUBSTANTIVE_CHARS = 12

    fun shouldPrefetch(userMessage: String): Boolean {
        val trimmed = userMessage.trim()
        if (trimmed.isEmpty()) return false
        val normalized = trimmed.lowercase().trimEnd('!', '.', '?', ',')
        if (probePatterns.any { normalized == it || normalized.startsWith("$it ") }) return false
        if (greetingPatterns.any { normalized == it || normalized.startsWith("$it ") }) return false
        if (trimmed.length < MIN_SUBSTANTIVE_CHARS) return false
        return true
    }

    /** Embed query from the current user text only (never from system prompt). */
    fun build(userMessage: String): String = userMessage.trim()
}
