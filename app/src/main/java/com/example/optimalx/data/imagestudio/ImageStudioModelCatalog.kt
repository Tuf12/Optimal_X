package com.example.optimalx.data.imagestudio

/**
 * xAI Grok Imagine model mapping for Image Studio v1.
 * Pricing reference: xAI docs / console — update when xAI changes rates.
 */
object ImageStudioModelCatalog {
    const val BACKEND_XAI_IMAGINE: String = "xai_imagine"
    const val PROVIDER_ID: String = "xai_imagine"

    private const val MODEL_DRAFT: String = "grok-imagine-image"
    private const val MODEL_QUALITY: String = "grok-imagine-image-quality"

    private const val COST_DRAFT_USD: Double = 0.02
    private const val COST_QUALITY_USD: Double = 0.05

    data class TierSpec(
        val modelId: String,
        val resolution: String,
        val estimatedUsd: Double,
    )

    fun tierSpec(tier: ImageTier): TierSpec = when (tier) {
        ImageTier.DRAFT -> TierSpec(
            modelId = MODEL_DRAFT,
            resolution = "1k",
            estimatedUsd = COST_DRAFT_USD,
        )
        ImageTier.QUALITY -> TierSpec(
            modelId = MODEL_QUALITY,
            resolution = "2k",
            estimatedUsd = COST_QUALITY_USD,
        )
    }

    fun estimateCost(tier: ImageTier): CostEstimate {
        val spec = tierSpec(tier)
        return CostEstimate(
            usd = spec.estimatedUsd,
            label = "~$%.2f".format(spec.estimatedUsd),
        )
    }
}
