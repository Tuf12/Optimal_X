package com.example.optimalx.data.imagestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageStudioModelCatalogTest {

    @Test
    fun tierSpec_mapsDraftAndQuality() {
        val draft = ImageStudioModelCatalog.tierSpec(ImageTier.DRAFT)
        assertEquals("grok-imagine-image", draft.modelId)
        assertEquals("1k", draft.resolution)
        assertEquals(0.02, draft.estimatedUsd, 0.0001)

        val quality = ImageStudioModelCatalog.tierSpec(ImageTier.QUALITY)
        assertEquals("grok-imagine-image-quality", quality.modelId)
        assertEquals("2k", quality.resolution)
        assertEquals(0.05, quality.estimatedUsd, 0.0001)
    }
}

class XaiImageGenerationServiceTest {

    @Test
    fun mapHttpError_authAndRateLimit() {
        val auth = XaiImageGenerationService.mapHttpError(401, """{"error":{"message":"bad key"}}""")
        assertEquals(ImageGenerationException.API_AUTH_FAILED, auth.code)

        val rate = XaiImageGenerationService.mapHttpError(429, "{}")
        assertEquals(ImageGenerationException.API_RATE_LIMIT, rate.code)
    }

    @Test
    fun isConfigured_reflectsApiKey() {
        val service = XaiImageGenerationService(
            apiKeyProvider = ImageGenerationApiKeyProvider { "xai-test" },
        )
        assertTrue(service.isConfigured())
    }
}
