package com.example.optimalx.data.eidos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosToolCatalogWorkshopTest {

    @Test
    fun editMode_includesSearchSemanticFirst() {
        val tools = EidosToolCatalog.toolsForWorkshopMode(WorkshopEidosMode.EDIT)
        assertEquals("search_semantic", tools.first().name)
        assertTrue(tools.any { it.name == "workshop_write_file" })
        assertTrue(tools.any { it.name == "call_panel_function" })
    }

    @Test
    fun chatMode_searchOnlyPlusOptionalRead() {
        val tools = EidosToolCatalog.toolsForWorkshopMode(WorkshopEidosMode.CHAT)
        assertEquals(listOf("search_semantic", "workshop_read_file"), tools.map { it.name })
    }

    @Test
    fun planMode_includesSearchAndSpecWrites() {
        val names = EidosToolCatalog.toolsForWorkshopMode(WorkshopEidosMode.PLAN).map { it.name }
        assertEquals("search_semantic", names.first())
        assertTrue(names.contains("workshop_write_file"))
        assertTrue(names.contains("workshop_create_file"))
        assertTrue(names.contains("workshop_replace_string"))
        assertTrue(!names.contains("call_panel_function"))
    }

    @Test
    fun legacyDebugMode_exposesEditToolSet() {
        val names = EidosToolCatalog.toolsForWorkshopMode(WorkshopEidosMode.DEBUG).map { it.name }
        assertTrue(names.contains("workshop_write_file"))
        assertTrue(names.contains("call_panel_function"))
    }
}
