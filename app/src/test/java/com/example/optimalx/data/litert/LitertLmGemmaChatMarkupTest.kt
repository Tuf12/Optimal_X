package com.example.optimalx.data.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LitertLmGemmaChatMarkupTest {

    @Test
    fun stripForDisplay_removesTurnTokens() {
        val raw = "<|turn>model\nstarted today?<turn|>\n<|turn>model"
        assertEquals("started today?", LitertLmGemmaChatMarkup.stripForDisplay(raw))
    }

    @Test
    fun stripForDisplay_preservesNormalText() {
        assertEquals(
            "Paris is the capital of France.",
            LitertLmGemmaChatMarkup.stripForDisplay("Paris is the capital of France."),
        )
    }

    @Test
    fun containsTurnMarkup_detectsLeakage() {
        assertTrue(LitertLmGemmaChatMarkup.containsTurnMarkup("<|turn>model"))
        assertFalse(LitertLmGemmaChatMarkup.containsTurnMarkup("Hello there"))
    }
}
