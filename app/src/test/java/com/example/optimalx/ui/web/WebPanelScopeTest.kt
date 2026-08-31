package com.example.optimalx.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPanelScopeTest {

    @Test
    fun editorScopeKey_usesSubfolderIdOnly() {
        assertEquals("editor:subfolder:42", WebPanelScope.editor(42L))
    }

    @Test
    fun normalize_collapsesLegacyEditorKeys() {
        assertEquals("editor:subfolder:7", WebPanelScope.normalize("editor:3:7"))
        assertEquals("editor:subfolder:7", WebPanelScope.normalize("editor_subfolder_7"))
    }

    @Test
    fun normalize_collapsesWidgetAliases() {
        assertEquals(WebPanelScope.WIDGET, WebPanelScope.normalize("widget_quick_web"))
        assertEquals(WebPanelScope.WIDGET, WebPanelScope.normalize(WebPanelScope.WIDGET))
    }

    @Test
    fun matches_doesNotCrossEditorAndWidget() {
        assertFalse(WebPanelScope.matches(WebPanelScope.WIDGET, WebPanelScope.editor(1L)))
        assertFalse(WebPanelScope.matches(WebPanelScope.editor(1L), WebPanelScope.WIDGET))
    }

    @Test
    fun matches_doesNotCrossSubfolders() {
        assertFalse(WebPanelScope.matches(WebPanelScope.editor(1L), WebPanelScope.editor(2L)))
        assertTrue(WebPanelScope.matches(WebPanelScope.editor(1L), "editor:9:1"))
    }

    @Test
    fun subfolderIdFromScopeKey_parsesCanonicalAndLegacy() {
        assertEquals(42L, WebPanelScope.subfolderIdFromScopeKey("editor:subfolder:42"))
        assertEquals(42L, WebPanelScope.subfolderIdFromScopeKey("editor:7:42"))
        assertNull(WebPanelScope.subfolderIdFromScopeKey(WebPanelScope.WIDGET))
    }
}
