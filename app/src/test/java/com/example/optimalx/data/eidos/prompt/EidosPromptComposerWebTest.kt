package com.example.optimalx.data.eidos.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerWebTest {

    @Test
    fun webWidgetPromptText_includesWebRulesAndUrl() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WEB_WIDGET)
        val prompt = EidosPromptComposer.webWidgetPromptText(
            profile = profile,
            webPanelPageUrl = "https://example.com/article",
        )

        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains("Web-scoped chat rules:"))
        assertTrue(prompt.contains("Loaded page URL: https://example.com/article"))
        assertFalse(prompt.contains(EidosSystemPromptLayers.NOTE_WRITE_RULES))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun webEditorPromptText_includesSubfolderContextAndWriteRules() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WEB_EDITOR)
        val prompt = EidosPromptComposer.webEditorPromptText(
            profile = profile,
            subfolderContext = """
                Current subfolder:
                Name: Research
                subfolderId: 12
                Note: empty.
            """.trimIndent(),
            webPanelPageUrl = "https://news.example.com/story",
            panelBridgeBlock = null,
            activeParentLine = "Active parent folder: Research Hub (parentFolderId=3)",
        )

        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains("Web-scoped chat rules:"))
        assertTrue(prompt.contains("Loaded page URL: https://news.example.com/story"))
        assertTrue(prompt.contains(EidosSystemPromptLayers.NOTE_WRITE_RULES))
        assertTrue(prompt.contains("subfolderId: 12"))
        assertTrue(prompt.contains("Active parent folder: Research Hub (parentFolderId=3)"))
        assertFalse(prompt.contains("Optional UI context"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }
}
