package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.PanelPlatformSpec
import com.example.optimalx.data.eidos.WorkshopEidosMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerWorkshopEditTest {

    @Test
    fun workshopEditPromptText_includesBridgeWhenEligible() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WORKSHOP_EDIT)
        val workshopContext = """
            Panel Workshop — custom HTML/JS panel project
            Project: Game Panel (subfolderId=15)
            Active Eidos mode: Edit (EDIT)

            ${PanelPlatformSpec.eidosInstructionsForMode(
                WorkshopEidosMode.EDIT,
                15,
                com.example.optimalx.data.eidos.WorkshopProjectPhase.LOGIC_REVIEW,
                docAlignScope = null,
                updateSection = null,
            )}

            ${PanelPlatformSpec.EIDOS_WORKSHOP_RETRIEVAL_POLICY}
        """.trimIndent()
        val prompt = EidosPromptComposer.workshopModePromptText(
            profile = profile,
            workshopContext = workshopContext,
            activeParentLine = "Active parent folder: Game Projects (parentFolderId=3)",
            editorSurfaceHint = "Editor tab: script.js",
            webPanelPageUrl = null,
            panelBridgeBlock = PanelPlatformSpec.inactivePanelBridgeContextBlock(),
        )

        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains("Active Eidos mode: Edit (EDIT)"))
        assertTrue(prompt.contains("Panel Bridge: inactive"))
        assertTrue(prompt.contains("Editor tab: script.js"))
        assertTrue(prompt.contains("Retrieved context may already include"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun workshopEditPromptText_omitsBridgeWhenNotProvided() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WORKSHOP_EDIT)
        val prompt = EidosPromptComposer.workshopModePromptText(
            profile = profile,
            workshopContext = "Panel Workshop context stub",
            activeParentLine = "Active parent folder: Projects (parentFolderId=1)",
            editorSurfaceHint = null,
            webPanelPageUrl = null,
            panelBridgeBlock = null,
        )

        assertFalse(prompt.contains("Panel Bridge"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }
}
