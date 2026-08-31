package com.example.optimalx.data.eidos

import com.example.optimalx.data.litert.LitertLmDefaults

/**
 * How each provider expects multi-turn + tool loops to be wired.
 *
 * - [RESPONSES_CHAINED]: xAI, OpenAI — `previous_response_id` + incremental `input` on tool continuations.
 * - [MESSAGES_CACHED]: Anthropic, Kimi — growing `messages` list + stable cached system/tools prefix.
 */
enum class EidosProviderFamily {
    RESPONSES_CHAINED,
    MESSAGES_CACHED,
    LOCAL_CONVERSATION,
}

fun providerFamily(activeProvider: String): EidosProviderFamily = when (activeProvider) {
    "openai", "xai" -> EidosProviderFamily.RESPONSES_CHAINED
    "anthropic", "kimi" -> EidosProviderFamily.MESSAGES_CACHED
    LitertLmDefaults.PROVIDER_ID -> EidosProviderFamily.LOCAL_CONVERSATION
    else -> EidosProviderFamily.RESPONSES_CHAINED
}

fun providerFamilyUsesIncrementalToolContinuation(family: EidosProviderFamily): Boolean =
    family == EidosProviderFamily.RESPONSES_CHAINED

/**
 * MESSAGES_CACHED providers (Kimi/Anthropic) grow the `messages` array on every hop. System prompt
 * omission on continuation is opt-in via [TransportHints.omitSystemOnContinuation] — default off
 * because omitting breaks prefix cache and drops manifest/subfolderId from model context.
 */
fun providerFamilyOmitsSystemOnToolContinuation(family: EidosProviderFamily): Boolean =
    family == EidosProviderFamily.MESSAGES_CACHED ||
        family == EidosProviderFamily.LOCAL_CONVERSATION
