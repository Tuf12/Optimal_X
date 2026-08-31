package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.EidosContextLimits
import com.example.optimalx.data.eidos.prefetch.EidosRetrievedContextBlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerPrefetchTest {

    @Test
    fun generalApp_includesRetrievedContextBlockWhenProvided() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.GENERAL_APP)
        val retrieved = """
            ${EidosRetrievedContextBlock.SECTION_HEADER}
            [1] LTM · Eidos Memory (score=0.82)
            User prefers dark mode
        """.trimIndent()
        val prompt = EidosPromptComposer.generalAppPromptText(
            profile = profile,
            webPanelPageUrl = null,
            retrievedContextBlock = retrieved,
        )
        assertTrue(prompt.contains(EidosRetrievedContextBlock.SECTION_HEADER))
        assertTrue(prompt.contains("User prefers dark mode"))
        val identityEnd = prompt.indexOf(EidosIdentityPrompt.TEXT) + EidosIdentityPrompt.TEXT.length
        val retrievedStart = prompt.indexOf(EidosRetrievedContextBlock.SECTION_HEADER)
        assertTrue(retrievedStart > identityEnd)
    }

    @Test
    fun generalApp_includesPrefetchRulesForMainChatProfile() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.GENERAL_APP)
        val prompt = EidosPromptComposer.generalAppPromptText(
            profile = profile,
            webPanelPageUrl = null,
        )
        assertTrue(prompt.contains(EidosContextLimits.PREFETCH_RETRIEVAL_RULES))
    }

    @Test
    fun workshopChat_includesPrefetchRules() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WORKSHOP_CHAT)
        val prompt = EidosPromptComposer.joinSections(
            EidosPromptComposer.userFacingPromptSections(profile),
        )
        assertTrue(prompt.contains(EidosContextLimits.PREFETCH_RETRIEVAL_RULES))
    }

    @Test
    fun generalApp_omitsRetrievedBlockWhenNull() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.GENERAL_APP)
        val prompt = EidosPromptComposer.generalAppPromptText(
            profile = profile,
            webPanelPageUrl = null,
            retrievedContextBlock = null,
        )
        assertFalse(prompt.contains("Passages relevant to the user's message"))
    }
}
