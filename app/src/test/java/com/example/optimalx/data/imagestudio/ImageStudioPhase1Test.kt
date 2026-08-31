package com.example.optimalx.data.imagestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageStudioMetadataTest {

    @Test
    fun buildAndParse_roundTrip() {
        val metadata = ImageStudioMetadata.build(
            prompt = "a red dragon",
            backend = "fal_cloud",
            modelId = "fal-ai/flux/schnell",
            tier = "draft",
            aspectRatio = "1:1",
            seed = 42,
            width = 1024,
            height = 1024,
        )

        assertEquals(ImageStudioMetadata.SOURCE, metadata.source)
        assertTrue(ImageStudioMetadata.isImageStudioMetadata(ImageStudioMetadata.stringify(metadata)))

        val parsed = ImageStudioMetadata.parse(ImageStudioMetadata.stringify(metadata))
        assertEquals("a red dragon", parsed?.prompt)
        assertEquals("fal-ai/flux/schnell", parsed?.modelId)
    }

    @Test
    fun parseInvalid_returnsNull() {
        assertEquals(null, ImageStudioMetadata.parse(null))
        assertEquals(null, ImageStudioMetadata.parse(""))
        assertEquals(null, ImageStudioMetadata.parse("not-json"))
        assertEquals(null, ImageStudioMetadata.stringify(null))
    }
}

class ImageStudioFilenameTest {

    @Test
    fun normalizeReadableName() {
        val result = ImageStudioFilename.normalize("cover art!!")
        assertTrue(result is ImageFileNameResult.Ok)
        assertEquals("cover-art.png", (result as ImageFileNameResult.Ok).fileName)
    }

    @Test
    fun normalizePreservesWebpExtension() {
        val result = ImageStudioFilename.normalize("Hero Banner.webp")
        assertTrue(result is ImageFileNameResult.Ok)
        assertEquals("hero-banner.webp", (result as ImageFileNameResult.Ok).fileName)
    }

    @Test
    fun normalizeRejectsPathSeparators() {
        val slash = ImageStudioFilename.normalize("cover/art.png")
        assertTrue(slash is ImageFileNameResult.Error)
        assertEquals("invalid_name", (slash as ImageFileNameResult.Error).code)

        val traversal = ImageStudioFilename.normalize("../cover.png")
        assertTrue(traversal is ImageFileNameResult.Error)
        assertEquals("invalid_name", (traversal as ImageFileNameResult.Error).code)
    }

    @Test
    fun normalizeRejectsEmpty() {
        val empty = ImageStudioFilename.normalize("   ")
        assertTrue(empty is ImageFileNameResult.Error)
        assertEquals("invalid_name", (empty as ImageFileNameResult.Error).code)
    }
}
