package com.example.optimalx.data.imagestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ImageStudioMetadataCostTest {

    @Test
    fun stringify_roundTripsCostFields() {
        val metadata = ImageStudioMetadata.build(
            prompt = "dragon",
            backend = ImageStudioModelCatalog.BACKEND_XAI_IMAGINE,
            modelId = "grok-imagine-image",
            tier = ImageTier.DRAFT.wireValue,
            aspectRatio = ImageAspectRatio.SQUARE.wireValue,
            estimatedCostUsd = 0.02,
            actualCostUsd = 0.019,
        )
        val raw = ImageStudioMetadata.stringify(metadata)
        val parsed = ImageStudioMetadata.parse(raw)
        assertNotNull(parsed)
        assertEquals(0.02, parsed!!.estimatedCostUsd!!, 0.0001)
        assertEquals(0.019, parsed.actualCostUsd!!, 0.0001)
    }
}
