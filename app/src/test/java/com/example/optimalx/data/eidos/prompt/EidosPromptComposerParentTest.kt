package com.example.optimalx.data.eidos.prompt

import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerParentTest {

    @Test
    fun parentPromptText_includesBoundedCatalogAndRules() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.PARENT)
        val prompt = EidosPromptComposer.parentPromptText(
            profile = profile,
            parentContext = """
                Current parent folder:
                Name: Kitchen Reno
                parentFolderId: 5

                All subfolders in this parent (2):
                - Cabinets (subfolderId=10)
                - Flooring (subfolderId=11)
            """.trimIndent(),
            webPanelPageUrl = null,
            dailyMemoryBlock = "Daily Memory (2026-06-22):\n(empty)",
        )

        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains(EidosSystemPromptLayers.PARENT_SCOPE_RULES))
        assertTrue(prompt.contains("All subfolders in this parent (2)"))
        assertTrue(prompt.contains("Daily Memory (2026-06-22):"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }
}
