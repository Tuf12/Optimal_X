package com.example.optimalx.data.eidos

/**
 * User-selected reasoning depth for Eidos chat (matches desktop low | medium | high).
 */
enum class EidosThinkingLevel(val wire: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    ;

    companion object {
        val DEFAULT = MEDIUM

        fun fromWire(raw: String?): EidosThinkingLevel =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: DEFAULT
    }
}

/**
 * Maps scope policy, provider, and user level to outbound request fields.
 */
object EidosThinkingResolver {
    fun providerSupportsUserThinkingLevel(provider: String): Boolean =
        provider == "kimi" || provider == "openai" || provider == "xai"

    data class Resolved(
        /** Kimi `thinking.type` enabled when true. */
        val thinkingEnabled: Boolean,
        /** OpenAI / xAI reasoning effort; null = omit reasoning knobs. */
        val reasoningEffort: String?,
    )

    fun resolve(
        scopeAllowsThinking: Boolean,
        userLevel: EidosThinkingLevel,
        provider: String,
    ): Resolved {
        if (!scopeAllowsThinking) {
            return Resolved(thinkingEnabled = false, reasoningEffort = null)
        }
        return when (provider) {
            "kimi" -> Resolved(
                thinkingEnabled = userLevel != EidosThinkingLevel.LOW,
                reasoningEffort = null,
            )
            "openai", "xai" -> Resolved(
                thinkingEnabled = true,
                reasoningEffort = userLevel.wire,
            )
            else -> Resolved(thinkingEnabled = false, reasoningEffort = null)
        }
    }
}
