package com.example.optimalx.data.eidos

/**
 * How each provider expects multi-turn + tool loops to be wired.
 *
 * - [RESPONSES_CHAINED]: xAI, OpenAI — `previous_response_id` + incremental `input` on tool continuations.
 * - [MESSAGES_CACHED]: Anthropic, Kimi — growing `messages` list + stable cached system/tools prefix.
 */
enum class EidosProviderFamily {
    RESPONSES_CHAINED,
    MESSAGES_CACHED,
}

fun providerFamily(activeProvider: String): EidosProviderFamily = when (activeProvider) {
    "openai", "xai" -> EidosProviderFamily.RESPONSES_CHAINED
    "anthropic", "kimi" -> EidosProviderFamily.MESSAGES_CACHED
    else -> EidosProviderFamily.RESPONSES_CHAINED
}

fun providerFamilyUsesIncrementalToolContinuation(family: EidosProviderFamily): Boolean =
    family == EidosProviderFamily.RESPONSES_CHAINED
