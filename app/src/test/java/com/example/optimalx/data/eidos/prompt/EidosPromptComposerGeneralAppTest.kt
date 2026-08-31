package com.example.optimalx.data.eidos.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerGeneralAppTest {

    @Test
    fun generalApp_includesRegistryOntologyLocationAndDailyMemory() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.GENERAL_APP)
        val prompt = EidosPromptComposer.generalAppPromptText(
            profile = profile,
            webPanelPageUrl = null,
            dailyMemoryBlock = """
                Daily Memory (2026-06-22):
                Working context for today — mention only when relevant to the user's message; use write_daily_memory to record new items.
                (empty)
            """.trimIndent(),
        )

        assertTrue(prompt.contains(EidosIdentityPrompt.TEXT))
        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains("Daily Memory (2026-06-22):"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun generalApp_includesOptionalWebPanelUrl() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.GENERAL_APP)
        val prompt = EidosPromptComposer.generalAppPromptText(
            profile = profile,
            webPanelPageUrl = "https://example.com/article",
        )
        assertTrue(prompt.contains("https://example.com/article"))
        assertTrue(prompt.contains("Loaded page URL"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun generalAppProfile_hasLocationBlock() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.GENERAL_APP)
        assertTrue(profile.locationBlock.contains("General chat"))
    }

    @Test
    fun generalApp_differsFromWidgetAskLocation() {
        val general = EidosScopeProfileRegistry.require(EidosScopeProfileIds.GENERAL_APP)
        val widget = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WIDGET_ASK)
        assertFalse(general.locationBlock == widget.locationBlock)
    }
}
