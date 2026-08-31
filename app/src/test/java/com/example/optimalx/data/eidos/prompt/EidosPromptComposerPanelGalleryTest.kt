package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.PanelPlatformSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerPanelGalleryTest {

    @Test
    fun panelGalleryPromptText_includesRegistryOntologyLocationAndRules() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.PANEL_GALLERY)
        val prompt = EidosPromptComposer.panelGalleryPromptText(
            profile = profile,
            projectContext = "Counts: 0 launchable (COMPLETE), 0 draft/in-progress\n\nProjects:\n(no panel projects yet)",
        )

        assertTrue(prompt.contains(EidosIdentityPrompt.TEXT))
        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains(PanelPlatformSpec.eidosPanelGalleryRules()))
        assertTrue(prompt.contains("Counts: 0 launchable"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun panelGalleryPromptText_omitsLegacyGalleryHeader() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.PANEL_GALLERY)
        val prompt = EidosPromptComposer.panelGalleryPromptText(
            profile = profile,
            projectContext = "Projects:\n(none)",
        )
        assertFalse(prompt.contains("Panel Gallery — browse and launch custom panels"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun panelGalleryProfile_hasLocationBlock() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.PANEL_GALLERY)
        assertTrue(profile.locationBlock.contains("Panel Gallery"))
    }
}
