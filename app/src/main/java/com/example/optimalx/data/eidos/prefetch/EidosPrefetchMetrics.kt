package com.example.optimalx.data.eidos.prefetch

data class EidosPrefetchMetrics(
    val passCount: Int = 0,
    val hitCount: Int = 0,
    val chars: Int = 0,
    val topScore: Float = 0f,
    val dailyHitsInBlock: Int = 0,
    val noteHitsInBlock: Int = 0,
    val skippedReason: String? = null,
) {
    fun toTraceCounts(): Map<String, Int> = buildMap {
        put("prefetchPassCount", passCount)
        put("prefetchHitCount", hitCount)
        put("prefetchChars", chars)
        put("prefetchTopScoreMilli", (topScore * 1000).toInt())
        put("prefetchDailyHits", dailyHitsInBlock)
        put("prefetchNoteHits", noteHitsInBlock)
        skippedReason?.let { put("prefetchSkipped", 1) }
    }

    companion object {
        fun skipped(reason: String) = EidosPrefetchMetrics(skippedReason = reason)
    }
}

data class EidosPrefetchResult(
    val block: String?,
    val metrics: EidosPrefetchMetrics,
    /** Daily-memory chunks included in [block] after gating and caps. */
    val dailyHitsInBlock: Int = 0,
    /** False when compose skipped prefetch before calling the service (greeting, flag off, etc.). */
    val serviceRan: Boolean = true,
)
