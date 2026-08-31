package com.example.optimalx.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchKeyTest {

    @Test
    fun normalizeWebSearchKey_trimsCaseAndWhitespace() {
        assertEquals("kotlin coroutines", normalizeWebSearchKey("  Kotlin   Coroutines  "))
    }

    @Test
    fun normalizeWebSearchKey_blankReturnsNull() {
        assertNull(normalizeWebSearchKey("   "))
    }

    @Test
    fun normalizeWebSearchKey_equivalentQueriesMatch() {
        val a = normalizeWebSearchKey("Foo  bar")
        val b = normalizeWebSearchKey("foo bar")
        assertEquals(a, b)
    }

    @Test
    fun displayWebSearchTitle_preservesReadableCasing() {
        assertEquals("Kotlin Coroutines", displayWebSearchTitle("  Kotlin   Coroutines  "))
    }

    @Test
    fun subfolderIdFromWebScopeKey_parsesEditorScope() {
        assertEquals(42L, subfolderIdFromWebScopeKey("editor:subfolder:42"))
        assertEquals(42L, subfolderIdFromWebScopeKey("editor:7:42"))
        assertNull(subfolderIdFromWebScopeKey(WebPanelScope.WIDGET))
    }

    @Test
    fun isWidgetWebScopeKey_recognizesLegacyAndCanonical() {
        assertTrue(isWidgetWebScopeKey(WebPanelScope.WIDGET))
        assertTrue(isWidgetWebScopeKey("widget_quick_web"))
        assertFalse(isWidgetWebScopeKey(WebPanelScope.editor(1L)))
    }
}
