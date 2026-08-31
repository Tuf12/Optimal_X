package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.prefetch.EidosPrefetchResult

/**
 * Resolves daily memory prompt text for main chat: prefetch chunks or a one-line fallback — never full blunt inject.
 */
object DailyMemoryPromptResolver {

    fun resolve(
        injectDailyMemory: Boolean,
        snapshot: DailyMemoryPromptSnapshot,
        prefetchResult: EidosPrefetchResult?,
    ): String? {
        if (!injectDailyMemory) return null

        if (snapshot.aiBlinded) {
            return DailyMemoryContext.formatPromptBlock(
                dateKey = snapshot.dateKey,
                content = "",
                aiBlinded = true,
            )
        }

        val skipReason = prefetchResult?.metrics?.skippedReason
        if (skipReason == "greeting_or_short" || skipReason == "empty_query") {
            return null
        }

        if (snapshot.content.isBlank()) return null

        if ((prefetchResult?.dailyHitsInBlock ?: 0) > 0) return null

        return DailyMemoryContext.formatPrefetchFallbackBlock(snapshot.dateKey)
    }
}

data class DailyMemoryPromptSnapshot(
    val dateKey: String,
    val content: String,
    val aiBlinded: Boolean,
)
