package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.EidosContextLimits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerInternalTest {

    @Test
    fun contentSummaryPrompt_omitsToolFirstAndIncludesTaskBlock() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.INTERNAL_CONTENT_SUMMARY)
        val composed = EidosPromptComposer.composeInternalJobPreview(profile)
        val prompt = composed.systemPrompt

        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(EidosInternalPromptBlocks.WORKSHOP_PROJECT_SUMMARY))
        assertFalse(prompt.contains(EidosContextLimits.TOOL_FIRST_CONTEXT_RULES))
        assertTrue(composed.stablePrefixSha256.isNotBlank())
        assertTrue(composed.sectionCharCountsJson.contains("ontology"))
    }

    @Test
    fun rolloverPrompt_includesVolatilePhaseBlock() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.INTERNAL_MEMORY_ROLLOVER)
        val volatile = RolloverPromptBlocks.buildRolloverSystemPrompt(
            phase = RolloverPromptPhase.DECISION,
            activePhase = com.example.optimalx.data.eidos.RolloverPhase.KING_INIT,
            allowedTools = listOf("read_daily_memory"),
        )
        val composed = EidosPromptComposer.composeInternalRolloverPreview(profile, volatile)
        val prompt = composed.systemPrompt

        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains("Return strict JSON only"))
        assertFalse(prompt.contains(EidosContextLimits.TOOL_FIRST_CONTEXT_RULES))
        assertTrue(composed.sectionCharCountsJson.contains("volatile"))
    }
}
