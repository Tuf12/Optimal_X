package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.EidosContextLimits
import org.junit.Assert.assertFalse

object EidosPromptComposerAssertions {

    /** Legacy monolith blocks — tool schemas and profile-specific rules replace these. */
    fun assertNoLegacyUniversalPromptBlocks(prompt: String) {
        assertFalse(
            "Prompt must not include TOOL_FIRST_CONTEXT_RULES",
            prompt.contains(EidosContextLimits.TOOL_FIRST_CONTEXT_RULES),
        )
        assertFalse(
            "Prompt must not include active location rule",
            prompt.contains(EidosSystemPromptLayers.ACTIVE_LOCATION_RULE),
        )
        assertFalse(
            "Prompt must not include provider web essay",
            prompt.contains("Provider-native web access"),
        )
        assertFalse(
            "Prompt must not include legacy active scope line",
            prompt.contains("Active scope:"),
        )
    }
}
