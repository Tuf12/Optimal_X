package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.prompt.EidosScopeProfileIds
import com.example.optimalx.data.eidos.prompt.EidosScopeProfileRegistry
import com.example.optimalx.data.model.ConversationScopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosToolCatalogTest {

    @Test
    fun allCatalog_includesEditNoteSection() {
        val names = EidosToolCatalog.all.map { it.name }.toSet()
        assertTrue(names.contains("edit_note_section"))
        assertTrue(names.contains("read_note"))
        assertTrue(names.contains("read_note_section"))
        assertTrue(names.contains("write_note"))
        assertTrue(names.contains("write_note_summary"))
        assertFalse(names.contains("append_note"))
        assertFalse(names.contains("note_replace_string"))
    }

    @Test
    fun toolsForScopedChat_subfolder_includesWriteNoteSummary() {
        val names = EidosToolCatalog.toolsForScopedChat(ConversationScopes.SUBFOLDER)
            .map { it.name }
            .toSet()
        assertTrue(names.contains("write_note_summary"))
        assertTrue(names.contains("search_semantic"))
    }

    @Test
    fun toolsForScopedChat_workshop_not_applicable_omitsWriteNoteSummary() {
        val names = EidosToolCatalog.toolsForWorkshopMode(
            WorkshopEidosMode.EDIT,
            WorkshopProjectPhase.DESIGN_BUILD,
        ).map { it.name }.toSet()
        assertFalse(names.contains("write_note_summary"))
    }

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
    fun toolsForProfile_generalApp_matchesRegistry() {
        val expected = EidosScopeProfileRegistry.require(EidosScopeProfileIds.GENERAL_APP).toolNames
        val profile = EidosToolCatalog.toolsForProfile(
            profileId = EidosScopeProfileIds.GENERAL_APP,
            scopeType = ConversationScopes.GENERAL,
            workshopMode = null,
            workshopPhase = null,
        ).map { it.name }.toSet()
        assertEquals(expected, profile)
    }

    @Test
    fun toolsByNames_returnsRequestedCatalogDefinitions() {
        val tools = EidosToolCatalog.toolsByNames("write_journal_entry", "write_long_term_memory")
        assertEquals(
            setOf("write_journal_entry", "write_long_term_memory"),
            tools.map { it.name }.toSet(),
        )
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
