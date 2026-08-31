package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.PanelPlatformSpec
import com.example.optimalx.data.eidos.WorkshopPanelContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerWorkshopChatTest {

    @Test
    fun workshopChatPromptText_includesWorkshopContextWithoutLegacyUniversalBlocks() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WORKSHOP_CHAT)
        val workshopContext = """
            Panel Workshop — custom HTML/JS panel project
            Project: Quiz Panel (subfolderId=9)
            Active Eidos mode: Chat (CHAT)

            Project files (use fileReferenceId with workshop_read_file / workshop_write_file):
            - index.html (html, fileReferenceId=1)

            ${PanelPlatformSpec.eidosChatModeInstructions(9)}

            ${WorkshopPanelContext.workshopContentPolicy(
                mode = com.example.optimalx.data.eidos.WorkshopEidosMode.CHAT,
                phase = com.example.optimalx.data.eidos.WorkshopProjectPhase.DESIGN_BUILD,
            )}

            ${PanelPlatformSpec.EIDOS_WORKSHOP_RETRIEVAL_POLICY}
        """.trimIndent()
        val prompt = EidosPromptComposer.workshopChatPromptText(
            profile = profile,
            workshopContext = workshopContext,
            activeParentLine = "Active parent folder: Workshop (parentFolderId=4)",
            editorSurfaceHint = "Editor tab: index.html",
            webPanelPageUrl = null,
        )

        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains("Panel Workshop — custom HTML/JS panel project"))
        assertTrue(prompt.contains(PanelPlatformSpec.EIDOS_WORKSHOP_RETRIEVAL_POLICY))
        assertTrue(prompt.contains("Retrieved context may already include"))
        assertTrue(prompt.contains("Active parent folder: Workshop (parentFolderId=4)"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }
}
