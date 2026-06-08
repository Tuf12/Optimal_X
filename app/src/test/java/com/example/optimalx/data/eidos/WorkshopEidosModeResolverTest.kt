package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosMessage
import com.example.optimalx.data.eidos.model.EidosRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopEidosModeResolverTest {

    @Test
    fun suggestMode_intakeUsesChat() {
        val mode = WorkshopEidosModeResolver.suggestMode(
            userMessage = "change the label",
            userOverride = null,
            phase = WorkshopProjectPhase.INTAKE,
        )
        assertEquals(WorkshopEidosMode.CHAT, mode)
    }

    @Test
    fun suggestMode_logicBuildDefaultsToChat() {
        val mode = WorkshopEidosModeResolver.suggestMode(
            userMessage = "wire the calculator",
            userOverride = null,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        assertEquals(WorkshopEidosMode.CHAT, mode)
    }

    @Test
    fun suggestMode_logicReviewDefaultsToChat() {
        val mode = WorkshopEidosModeResolver.suggestMode(
            userMessage = "change the label",
            userOverride = null,
            phase = WorkshopProjectPhase.LOGIC_REVIEW,
        )
        assertEquals(WorkshopEidosMode.CHAT, mode)
    }

    @Test
    fun suggestMode_logicReviewEditOnBrokenKeyword() {
        val mode = WorkshopEidosModeResolver.suggestMode(
            userMessage = "preview is broken",
            userOverride = null,
            phase = WorkshopProjectPhase.LOGIC_REVIEW,
        )
        assertEquals(WorkshopEidosMode.EDIT, mode)
    }

    @Test
    fun suggestMode_updateDefaultsToChat() {
        val mode = WorkshopEidosModeResolver.suggestMode(
            userMessage = "move the button",
            userOverride = null,
            phase = WorkshopProjectPhase.UPDATE,
        )
        assertEquals(WorkshopEidosMode.CHAT, mode)
    }

    @Test
    fun suggestMode_updatePlanKeywordUsesPlan() {
        val mode = WorkshopEidosModeResolver.suggestMode(
            userMessage = "update the flow.md spec",
            userOverride = null,
            phase = WorkshopProjectPhase.UPDATE,
        )
        assertEquals(WorkshopEidosMode.PLAN, mode)
    }

    @Test
    fun suggestMode_completeDefaultsToChat() {
        val mode = WorkshopEidosModeResolver.suggestMode(
            userMessage = "tweak the layout",
            userOverride = null,
            phase = WorkshopProjectPhase.COMPLETE,
        )
        assertEquals(WorkshopEidosMode.CHAT, mode)
    }

    @Test
    fun coerceModeForPhase_designBuildKickoffForcesBuildDesignWhenStoredPlan() {
        assertEquals(
            WorkshopEidosMode.BUILD_DESIGN,
            WorkshopEidosModeResolver.coerceModeForPhase(
                WorkshopEidosMode.PLAN,
                WorkshopProjectPhase.DESIGN_BUILD,
                activeBuildKickoff = WorkshopBuildKickoff.DESIGN,
            ),
        )
    }

    @Test
    fun modeForActiveBuildKickoff_mapsKickoffToInternalMode() {
        assertEquals(
            WorkshopEidosMode.BUILD_DESIGN,
            WorkshopEidosModeResolver.modeForActiveBuildKickoff(
                WorkshopProjectPhase.DESIGN_BUILD,
                WorkshopBuildKickoff.DESIGN,
            ),
        )
        assertEquals(
            WorkshopEidosMode.BUILD_LOGIC,
            WorkshopEidosModeResolver.modeForActiveBuildKickoff(
                WorkshopProjectPhase.LOGIC_BUILD,
                WorkshopBuildKickoff.LOGIC,
            ),
        )
    }

    @Test
    fun coerceModeForPhase_designBuildPreservesPlan() {
        assertEquals(
            WorkshopEidosMode.PLAN,
            WorkshopEidosModeResolver.coerceModeForPhase(
                WorkshopEidosMode.PLAN,
                WorkshopProjectPhase.DESIGN_BUILD,
            ),
        )
    }

    @Test
    fun coerceModeForPhase_designBuildPreservesEdit() {
        assertEquals(
            WorkshopEidosMode.EDIT,
            WorkshopEidosModeResolver.coerceModeForPhase(
                WorkshopEidosMode.EDIT,
                WorkshopProjectPhase.DESIGN_BUILD,
            ),
        )
    }

    @Test
    fun coerceModeForPhase_logicBuildNormalizesLegacyDebugToEdit() {
        assertEquals(
            WorkshopEidosMode.EDIT,
            WorkshopEidosModeResolver.coerceModeForPhase(
                WorkshopEidosMode.DEBUG,
                WorkshopProjectPhase.LOGIC_BUILD,
            ),
        )
    }

    @Test
    fun isBuildKickoffModeActive_onlyWithActiveKickoffFlag() {
        assertTrue(
            WorkshopEidosModeResolver.isBuildKickoffModeActive(
                WorkshopEidosMode.BUILD_LOGIC,
                WorkshopProjectPhase.LOGIC_BUILD,
                WorkshopBuildKickoff.LOGIC,
            ),
        )
        assertFalse(
            WorkshopEidosModeResolver.isBuildKickoffModeActive(
                WorkshopEidosMode.BUILD_LOGIC,
                WorkshopProjectPhase.LOGIC_BUILD,
                activeBuildKickoff = null,
            ),
        )
        assertFalse(
            WorkshopEidosModeResolver.isBuildKickoffModeActive(
                WorkshopEidosMode.BUILD_LOGIC,
                WorkshopProjectPhase.LOGIC_REVIEW,
                WorkshopBuildKickoff.LOGIC,
            ),
        )
    }

    @Test
    fun coerceModeForPhase_designBuildPreservesBuildDesignOnlyDuringKickoff() {
        assertEquals(
            WorkshopEidosMode.BUILD_DESIGN,
            WorkshopEidosModeResolver.coerceModeForPhase(
                WorkshopEidosMode.BUILD_DESIGN,
                WorkshopProjectPhase.DESIGN_BUILD,
                activeBuildKickoff = WorkshopBuildKickoff.DESIGN,
            ),
        )
        assertEquals(
            WorkshopEidosMode.EDIT,
            WorkshopEidosModeResolver.coerceModeForPhase(
                WorkshopEidosMode.BUILD_DESIGN,
                WorkshopProjectPhase.DESIGN_BUILD,
            ),
        )
    }

    @Test
    fun coerceModeForPhase_designBuildPreservesChat() {
        assertEquals(
            WorkshopEidosMode.CHAT,
            WorkshopEidosModeResolver.coerceModeForPhase(
                WorkshopEidosMode.CHAT,
                WorkshopProjectPhase.DESIGN_BUILD,
            ),
        )
    }

    @Test
    fun coerceModeForPhase_docAlignPreservesPlanDuringLogicBuild() {
        assertEquals(
            WorkshopEidosMode.PLAN,
            WorkshopEidosModeResolver.coerceModeForPhase(
                WorkshopEidosMode.PLAN,
                WorkshopProjectPhase.LOGIC_BUILD,
                docAlignScope = WorkshopDocAlignScope.DESIGN,
            ),
        )
    }

    @Test
    fun coerceModeForPhase_docAlignPreservesPlanDuringDesignBuild() {
        assertEquals(
            WorkshopEidosMode.PLAN,
            WorkshopEidosModeResolver.coerceModeForPhase(
                WorkshopEidosMode.PLAN,
                WorkshopProjectPhase.DESIGN_BUILD,
                docAlignScope = WorkshopDocAlignScope.DESIGN,
            ),
        )
    }

    @Test
    fun shouldNudgeNewChat_atThreshold() {
        assertTrue(WorkshopEidosModeResolver.shouldNudgeNewChat(10))
    }

    private fun msg(role: EidosRole, text: String) = EidosMessage(role = role, content = text)
}
