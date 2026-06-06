package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopEidosModeTest {

    @Test
    fun userChipModes_areChatPlanEditOnly() {
        assertEquals(
            listOf(WorkshopEidosMode.CHAT, WorkshopEidosMode.PLAN, WorkshopEidosMode.EDIT),
            WorkshopEidosMode.USER_CHIP_MODES,
        )
    }

    @Test
    fun visibleInSelector_onlyUserChips() {
        assertTrue(WorkshopEidosMode.CHAT.visibleInSelector)
        assertTrue(WorkshopEidosMode.PLAN.visibleInSelector)
        assertTrue(WorkshopEidosMode.EDIT.visibleInSelector)
        assertFalse(WorkshopEidosMode.DEBUG.visibleInSelector)
        assertFalse(WorkshopEidosMode.BUILD_DESIGN.visibleInSelector)
    }

    @Test
    fun normalizeToUserChip_mapsLegacyModesToEdit() {
        assertEquals(WorkshopEidosMode.EDIT, WorkshopEidosMode.normalizeToUserChip(WorkshopEidosMode.DEBUG))
        assertEquals(WorkshopEidosMode.EDIT, WorkshopEidosMode.normalizeToUserChip(WorkshopEidosMode.DESIGN))
        assertEquals(WorkshopEidosMode.EDIT, WorkshopEidosMode.normalizeToUserChip(WorkshopEidosMode.BUILD_LOGIC))
        assertEquals(WorkshopEidosMode.CHAT, WorkshopEidosMode.normalizeToUserChip(WorkshopEidosMode.CHAT))
    }

    @Test
    fun effectiveForInstructions_preservesBuildKickoff() {
        assertEquals(
            WorkshopEidosMode.BUILD_DESIGN,
            WorkshopEidosMode.effectiveForInstructions(
                WorkshopEidosMode.BUILD_DESIGN,
                WorkshopProjectPhase.DESIGN_BUILD,
                docAlignScope = null,
            ),
        )
        assertEquals(
            WorkshopEidosMode.EDIT,
            WorkshopEidosMode.effectiveForInstructions(
                WorkshopEidosMode.DEBUG,
                WorkshopProjectPhase.LOGIC_REVIEW,
                docAlignScope = null,
            ),
        )
    }
}
