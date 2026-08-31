package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.PanelPlatformSpec
import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopPanelContext
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerWorkshopPlanTest {

    @Test
    fun workshopPlanPromptText_includesPlanContextWithoutLegacyUniversalBlocks() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WORKSHOP_PLAN)
        val workshopContext = """
            Panel Workshop — custom HTML/JS panel project
            Project: Budget Panel (subfolderId=11)
            Active Eidos mode: Plan (PLAN)

            Project files (use fileReferenceId with workshop_read_file / workshop_write_file):
            - SPEC.md (md, fileReferenceId=2)

            ${PanelPlatformSpec.eidosInstructionsForMode(
                WorkshopEidosMode.PLAN,
                11,
                WorkshopProjectPhase.SPEC_REVIEW,
                docAlignScope = null,
                updateSection = null,
            )}

            ${WorkshopPanelContext.workshopContentPolicy(
                mode = WorkshopEidosMode.PLAN,
                phase = WorkshopProjectPhase.SPEC_REVIEW,
            )}

            ${PanelPlatformSpec.EIDOS_WORKSHOP_RETRIEVAL_POLICY}
        """.trimIndent()
        val prompt = EidosPromptComposer.workshopModePromptText(
            profile = profile,
            workshopContext = workshopContext,
            activeParentLine = "Active parent folder: Workshop Root (parentFolderId=6)",
            editorSurfaceHint = null,
            webPanelPageUrl = null,
        )

        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains("Active Eidos mode: Plan (PLAN)"))
        assertTrue(prompt.contains(PanelPlatformSpec.EIDOS_WORKSHOP_RETRIEVAL_POLICY))
        assertTrue(prompt.contains("Retrieved context may already include"))
        assertTrue(prompt.contains("Active parent folder: Workshop Root (parentFolderId=6)"))
        assertFalse(prompt.contains("Panel Bridge"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }
}
