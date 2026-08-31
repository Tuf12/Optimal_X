package com.example.optimalx.data.imagestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageStudioDraftParserTest {

    private val sample = """
        Here is a draft you can send to Image Studio:

        ### Image Studio draft
        **Prompt:** A red dragon coiled on a cliff at sunset, cinematic lighting, detailed scales, wide composition, fantasy illustration style.
        **Negative:** blurry, watermark, text, extra limbs
        **Aspect:** 16:9
        **Suggested name:** cover-art-dragon

        Let me know if you want changes.
    """.trimIndent()

    @Test
    fun hasImageStudioDraft_detectsHeading() {
        assertTrue(ImageStudioDraftParser.hasImageStudioDraft(sample))
        assertFalse(ImageStudioDraftParser.hasImageStudioDraft("no draft here"))
    }

    @Test
    fun parseDraft_extractsFields() {
        val draft = ImageStudioDraftParser.parse(sample)
        assertNotNull(draft)
        assertTrue(draft!!.prompt.contains("red dragon"))
        assertEquals("blurry, watermark, text, extra limbs", draft.negativePrompt)
        assertEquals("16:9", draft.aspectRatio)
        assertEquals("cover-art-dragon", draft.suggestedName)
    }

    @Test
    fun parseDraft_multilinePrompt() {
        val text = """
            ### Image Studio draft
            **Prompt:** Line one of the scene.
            Line two with more detail.
            **Aspect:** 1:1
            **Suggested name:** scene-v1
        """.trimIndent()
        val draft = ImageStudioDraftParser.parse(text)
        assertNotNull(draft)
        assertTrue(draft!!.prompt.contains("Line one"))
        assertTrue(draft.prompt.contains("Line two"))
        assertEquals("1:1", draft.aspectRatio)
    }

    @Test
    fun extractDraftBlock_stopsAtNextHeading() {
        val block = ImageStudioDraftParser.extractDraftBlock(
            """
            ### Image Studio draft
            **Prompt:** only this
            ### Other section
            """.trimIndent(),
        )
        assertNotNull(block)
        assertFalse(block!!.contains("Other section"))
    }

    @Test
    fun parseDraft_invalidAspectIgnored() {
        val draft = ImageStudioDraftParser.parse(
            """
            ### Image Studio draft
            **Prompt:** test image
            **Aspect:** 21:9
            """.trimIndent(),
        )
        assertNotNull(draft)
        assertNull(draft!!.aspectRatio)
    }
}
