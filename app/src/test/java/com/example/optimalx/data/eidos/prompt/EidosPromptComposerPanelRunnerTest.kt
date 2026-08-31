package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.PanelPlatformSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerPanelRunnerTest {

    @Test
    fun panelRunnerPromptText_includesRunnerContextAndBridge() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.PANEL_RUNNER)
        val prompt = EidosPromptComposer.panelRunnerPromptText(
            profile = profile,
            runnerContext = """
                Panel Runner — user is using panel "Trivia" full screen (not building).
                workshopSubfolderId=9, phase=Complete.
                Chat scope: panel_runner (separate from panel_workshop build threads).

                ${PanelPlatformSpec.eidosRunnerInstructions(9)}
            """.trimIndent(),
            panelBridgeBlock = PanelPlatformSpec.inactivePanelRunnerBridgeContextBlock(),
            activeParentLine = "Active parent folder: Panels (parentFolderId=2)",
            editorSurfaceHint = null,
            webPanelPageUrl = null,
        )

        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains("Panel Runner — user is using panel \"Trivia\""))
        assertTrue(prompt.contains(PanelPlatformSpec.eidosRunnerInstructions(9)))
        assertTrue(prompt.contains("Panel Bridge: inactive"))
        assertTrue(prompt.contains("Active parent folder: Panels (parentFolderId=2)"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun panelRunnerPromptText_includesActiveBridgeWhenProvided() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.PANEL_RUNNER)
        val prompt = EidosPromptComposer.panelRunnerPromptText(
            profile = profile,
            runnerContext = "Panel Runner context stub",
            panelBridgeBlock = "Panel Bridge (ACTIVE — call_panel_function will reach live panel JS):",
            activeParentLine = "Active parent folder: Main (parentFolderId=1)",
            editorSurfaceHint = "Runner screen",
            webPanelPageUrl = null,
        )

        assertTrue(prompt.contains("Panel Bridge (ACTIVE"))
        assertTrue(prompt.contains("Runner screen"))
        assertFalse(prompt.contains("Panel Bridge: inactive"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }
}
