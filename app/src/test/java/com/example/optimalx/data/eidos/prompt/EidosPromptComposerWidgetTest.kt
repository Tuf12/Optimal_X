package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.EidosContextLimits
import com.example.optimalx.data.eidos.prefetch.EidosRetrievedContextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerWidgetTest {

    @Test
    fun widgetAsk_includesPrefetchRules() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WIDGET_ASK)
        val prompt = EidosPromptComposer.widgetSurfacePromptText(profile)
        assertTrue(prompt.contains(EidosContextLimits.PREFETCH_RETRIEVAL_RULES))
    }

    @Test
    fun widgetAsk_includesRetrievedBlockWhenProvided() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WIDGET_ASK)
        val retrieved = """
            ${EidosRetrievedContextBlock.SECTION_HEADER}
            [1] LTM · Eidos Memory (score=0.82)
            User prefers dark mode
        """.trimIndent()
        val prompt = EidosPromptComposer.widgetSurfacePromptText(
            profile = profile,
            retrievedContextBlock = retrieved,
        )
        assertTrue(prompt.contains(EidosRetrievedContextBlock.SECTION_HEADER))
        assertTrue(prompt.contains("User prefers dark mode"))
    }

    @Test
    fun widgetAsk_promptIsIdentityOntologyAndLocationOnly() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WIDGET_ASK)
        val prompt = EidosPromptComposer.widgetSurfacePromptText(profile)

        assertEquals(
            EidosPromptComposer.joinSections(EidosPromptComposer.userFacingPromptSections(profile)),
            prompt,
        )
        assertTrue(prompt.contains(EidosIdentityPrompt.TEXT))
        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains("Ask Eidos"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun widgetChat_promptIsIdentityOntologyAndLocationOnly() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WIDGET_CHAT)
        val prompt = EidosPromptComposer.widgetSurfacePromptText(profile)

        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains("Widget Chat"))
        assertTrue(prompt.contains(EidosSystemPromptLayers.GENERAL_SCOPE_RULES))
        assertTrue(prompt.contains("optimalx://"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun widgetAsk_andWidgetChat_haveDifferentLocationBlocks() {
        val ask = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WIDGET_ASK)
        val chat = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WIDGET_CHAT)
        assertNotEquals(ask.locationBlock, chat.locationBlock)
        assertNotEquals(ask.ontologyBlock, chat.ontologyBlock)
    }

    @Test
    fun widgetProfiles_haveLocationBlocks() {
        listOf(EidosScopeProfileIds.WIDGET_ASK, EidosScopeProfileIds.WIDGET_CHAT).forEach { profileId ->
            val profile = EidosScopeProfileRegistry.require(profileId)
            assertTrue(profile.locationBlock.isNotBlank())
        }
    }
}
