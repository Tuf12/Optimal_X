package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopProjectPhaseTest {

    @Test
    fun fromStored_parsesEnumName() {
        assertEquals(WorkshopProjectPhase.DESIGN_REVIEW, WorkshopProjectPhase.fromStored("DESIGN_REVIEW"))
        assertEquals(WorkshopProjectPhase.INTAKE, WorkshopProjectPhase.fromStored("intake"))
    }

    @Test
    fun fromStored_returnsNullForBlank() {
        assertNull(WorkshopProjectPhase.fromStored(null))
        assertNull(WorkshopProjectPhase.fromStored(""))
        assertNull(WorkshopProjectPhase.fromStored("not_a_phase"))
    }

    @Test
    fun resolveFromLegacy_usesStoredWhenPresent() {
        assertEquals(
            WorkshopProjectPhase.SPEC_REVIEW,
            WorkshopProjectPhase.resolveFromLegacy("SPEC_REVIEW", initialBuildSent = false),
        )
    }

    @Test
    fun resolveFromLegacy_migratesV1BuildSent() {
        assertEquals(
            WorkshopProjectPhase.COMPLETE,
            WorkshopProjectPhase.resolveFromLegacy(null, initialBuildSent = true),
        )
    }

    @Test
    fun resolveFromLegacy_migratesV1NotBuilt() {
        assertEquals(
            WorkshopProjectPhase.INTAKE,
            WorkshopProjectPhase.resolveFromLegacy(null, initialBuildSent = false),
        )
    }

    @Test
    fun allowsDebugMode_fromLogicBuildOnward() {
        assertFalse(WorkshopProjectPhase.INTAKE.allowsDebugMode)
        assertFalse(WorkshopProjectPhase.DESIGN_REVIEW.allowsDebugMode)
        assertTrue(WorkshopProjectPhase.LOGIC_BUILD.allowsDebugMode)
        assertTrue(WorkshopProjectPhase.COMPLETE.allowsDebugMode)
    }

    @Test
    fun freezesMarkdown_duringDesignReviewAndUnifiedUpdate() {
        assertTrue(WorkshopProjectPhase.DESIGN_REVIEW.freezesMarkdownForEidos())
        assertTrue(WorkshopProjectPhase.UPDATE.freezesMarkdownForEidos())
        assertFalse(WorkshopProjectPhase.LOGIC_REVIEW.freezesMarkdownForEidos())
    }

    @Test
    fun allowsCallPanelFunction_fromDesignBuildOnward() {
        assertFalse(WorkshopProjectPhase.INTAKE.allowsCallPanelFunction)
        assertFalse(WorkshopProjectPhase.SPEC_REVIEW.allowsCallPanelFunction)
        assertTrue(WorkshopProjectPhase.DESIGN_BUILD.allowsCallPanelFunction)
    }

    @Test
    fun phaseLabel_unifiedUpdateNoSectionSuffix() {
        assertEquals(
            "Update/edit",
            WorkshopProjectPhase.UPDATE.phaseLabel(WorkshopUpdateSection.DESIGN),
        )
    }

    @Test
    fun defaultEidosMode_phaseDefaults() {
        assertEquals(WorkshopEidosMode.CHAT, WorkshopProjectPhase.INTAKE.defaultEidosMode())
        assertEquals(WorkshopEidosMode.PLAN, WorkshopProjectPhase.SPEC_REVIEW.defaultEidosMode())
        assertEquals(WorkshopEidosMode.CHAT, WorkshopProjectPhase.DESIGN_REVIEW.defaultEidosMode())
        assertEquals(WorkshopEidosMode.CHAT, WorkshopProjectPhase.LOGIC_REVIEW.defaultEidosMode())
        assertEquals(WorkshopEidosMode.CHAT, WorkshopProjectPhase.COMPLETE.defaultEidosMode())
    }

    @Test
    fun resolveStoredEidosMode_preservesPlanChipOnDesignBuild() {
        assertEquals(
            WorkshopEidosMode.PLAN,
            WorkshopProjectPhase.DESIGN_BUILD.resolveStoredEidosMode(WorkshopEidosMode.PLAN),
        )
        assertEquals(
            WorkshopEidosMode.EDIT,
            WorkshopProjectPhase.DESIGN_BUILD.resolveStoredEidosMode(WorkshopEidosMode.BUILD_DESIGN),
        )
        assertEquals(
            WorkshopEidosMode.BUILD_DESIGN,
            WorkshopProjectPhase.DESIGN_BUILD.resolveStoredEidosMode(
                WorkshopEidosMode.BUILD_DESIGN,
                activeBuildKickoff = WorkshopBuildKickoff.DESIGN,
            ),
        )
    }

    @Test
    fun resolveStoredEidosMode_mapsLegacyDebugToEdit() {
        assertEquals(
            WorkshopEidosMode.EDIT,
            WorkshopProjectPhase.LOGIC_REVIEW.resolveStoredEidosMode(WorkshopEidosMode.DEBUG),
        )
    }

    @Test
    fun resolveStoredEidosMode_mapsStaleBuildLogicToEditOnLogicReview() {
        assertEquals(
            WorkshopEidosMode.EDIT,
            WorkshopProjectPhase.LOGIC_REVIEW.resolveStoredEidosMode(WorkshopEidosMode.BUILD_LOGIC),
        )
    }

    @Test
    fun resolveStoredEidosMode_mapsStaleBuildLogicToEditWithoutActiveKickoff() {
        assertEquals(
            WorkshopEidosMode.EDIT,
            WorkshopProjectPhase.LOGIC_BUILD.resolveStoredEidosMode(
                WorkshopEidosMode.BUILD_LOGIC,
                logicBehaviorReady = true,
            ),
        )
        assertEquals(
            WorkshopEidosMode.EDIT,
            WorkshopProjectPhase.LOGIC_BUILD.resolveStoredEidosMode(
                WorkshopEidosMode.BUILD_LOGIC,
                logicBehaviorReady = false,
            ),
        )
        assertEquals(
            WorkshopEidosMode.BUILD_LOGIC,
            WorkshopProjectPhase.LOGIC_BUILD.resolveStoredEidosMode(
                WorkshopEidosMode.BUILD_LOGIC,
                activeBuildKickoff = WorkshopBuildKickoff.LOGIC,
            ),
        )
    }

    @Test
    fun selectorModes_threeChipsExceptIntake() {
        assertEquals(
            listOf(WorkshopEidosMode.CHAT),
            WorkshopProjectPhase.INTAKE.selectorModes(),
        )
        assertEquals(
            WorkshopEidosMode.USER_CHIP_MODES,
            WorkshopProjectPhase.DESIGN_BUILD.selectorModes(),
        )
        assertEquals(
            WorkshopEidosMode.USER_CHIP_MODES,
            WorkshopProjectPhase.UPDATE.selectorModes(),
        )
    }

    @Test
    fun defaultEidosMode_updateIsChat() {
        assertEquals(WorkshopEidosMode.CHAT, WorkshopProjectPhase.UPDATE.defaultEidosMode())
    }
}
