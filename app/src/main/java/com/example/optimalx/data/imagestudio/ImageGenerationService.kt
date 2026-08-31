package com.example.optimalx.data.imagestudio

interface ImageGenerationService {
    suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult

    fun estimateCost(request: ImageGenerationRequest): CostEstimate?

    fun isConfigured(): Boolean
}
