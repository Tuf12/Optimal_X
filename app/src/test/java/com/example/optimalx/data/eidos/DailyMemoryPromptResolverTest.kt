package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.prefetch.EidosPrefetchMetrics
import com.example.optimalx.data.eidos.prefetch.EidosPrefetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyMemoryPromptResolverTest {

    private val snapshot = DailyMemoryPromptSnapshot(
        dateKey = "2026-07-10",
        content = "Morning standup at 9am.",
        aiBlinded = false,
    )

    @Test
    fun injectDisabled_returnsNull() {
        assertNull(
            DailyMemoryPromptResolver.resolve(
                injectDailyMemory = false,
                snapshot = snapshot,
                prefetchResult = null,
            ),
        )
    }

    @Test
    fun dailyHitsInPrefetch_omitsSeparateDailyBlock() {
        val prefetchResult = EidosPrefetchResult(
            block = "## Retrieved context",
            metrics = EidosPrefetchMetrics(dailyHitsInBlock = 1),
            dailyHitsInBlock = 1,
        )
        assertNull(
            DailyMemoryPromptResolver.resolve(
                injectDailyMemory = true,
                snapshot = snapshot,
                prefetchResult = prefetchResult,
            ),
        )
    }

    @Test
    fun noDailyHitsButContentExists_usesFallbackLine() {
        val prefetchResult = EidosPrefetchResult(
            block = null,
            metrics = EidosPrefetchMetrics(skippedReason = "below_threshold"),
            dailyHitsInBlock = 0,
        )
        val block = DailyMemoryPromptResolver.resolve(
            injectDailyMemory = true,
            snapshot = snapshot,
            prefetchResult = prefetchResult,
        )
        assertEquals(
            DailyMemoryContext.formatPrefetchFallbackBlock("2026-07-10"),
            block,
        )
    }

    @Test
    fun greetingSkip_omitsDailyBlock() {
        val prefetchResult = EidosPrefetchResult(
            block = null,
            metrics = EidosPrefetchMetrics.skipped("greeting_or_short"),
            serviceRan = false,
        )
        assertNull(
            DailyMemoryPromptResolver.resolve(
                injectDailyMemory = true,
                snapshot = snapshot,
                prefetchResult = prefetchResult,
            ),
        )
    }

    @Test
    fun aiBlinded_stillShowsBlindNotice() {
        val blindSnapshot = snapshot.copy(aiBlinded = true)
        val block = DailyMemoryPromptResolver.resolve(
            injectDailyMemory = true,
            snapshot = blindSnapshot,
            prefetchResult = null,
        )
        assertTrue(block!!.contains("AI access disabled"))
    }
}
