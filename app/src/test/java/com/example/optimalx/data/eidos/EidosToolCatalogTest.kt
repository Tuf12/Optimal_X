package com.example.optimalx.data.eidos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosToolCatalogTest {

    @Test
    fun toolsForWorkshopMode_intakeIsChatOnly() {
        val names = EidosToolCatalog.toolsForWorkshopMode(
            WorkshopEidosMode.EDIT,
            WorkshopProjectPhase.INTAKE,
        ).map { it.name }.toSet()

        assertTrue(names.contains("search_semantic"))
        assertTrue(names.contains("workshop_read_file"))
        assertFalse(names.contains("workshop_write_file"))
        assertFalse(names.contains("call_panel_function"))
    }

    @Test
    fun toolsForWorkshopMode_specReviewEditOmitsCallPanel() {
        val names = EidosToolCatalog.toolsForWorkshopMode(
            WorkshopEidosMode.EDIT,
            WorkshopProjectPhase.SPEC_REVIEW,
        ).map { it.name }.toSet()

        assertFalse(names.contains("call_panel_function"))
        assertTrue(names.contains("workshop_write_file"))
    }

    @Test
    fun toolsForWorkshopMode_designBuildIncludesCallPanel() {
        val names = EidosToolCatalog.toolsForWorkshopMode(
            WorkshopEidosMode.BUILD_DESIGN,
            WorkshopProjectPhase.DESIGN_BUILD,
        ).map { it.name }.toSet()

        assertTrue(names.contains("call_panel_function"))
    }

    @Test
    fun toolsForWorkshopMode_planOmitsCallPanelRegardlessOfPhase() {
        val names = EidosToolCatalog.toolsForWorkshopMode(
            WorkshopEidosMode.PLAN,
            WorkshopProjectPhase.LOGIC_REVIEW,
        ).map { it.name }.toSet()

        assertFalse(names.contains("call_panel_function"))
    }
}
