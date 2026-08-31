package com.example.optimalx.data.eidos

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class EidosNavigationResolverTest {

    @Test
    fun parseIdFromToolContent_readsIdMarker() {
        assertEquals(
            42L,
            EidosNavigationResolver.parseIdFromToolContent(
                "Created subfolder 'Foo' (id=42) under parent 'Jobs'",
            ),
        )
    }

    @Test
    fun parseSubfolderIdFromToolContent_readsJsonStyleMarker() {
        assertEquals(
            9L,
            EidosNavigationResolver.parseSubfolderIdFromToolContent("""{"subfolderId":9}"""),
        )
    }

    @Test
    fun parseSubfolderIdFromToolContent_readsNoteWriteSuffix() {
        assertEquals(
            42L,
            EidosNavigationResolver.parseSubfolderIdFromToolContent(
                "Note updated (subfolderId=42)",
            ),
        )
        assertEquals(
            7L,
            EidosNavigationResolver.parseSubfolderIdFromToolContent(
                "Note change proposal queued for review. (subfolderId=7)",
            ),
        )
    }

    @Test
    fun resolveWorkshopToolSubfolderId_prefersArgsThenScope() {
        val args = buildJsonObject { put("subfolderId", 3) }
        val fromArgs = EidosNavigationResolver.resolveWorkshopToolSubfolderId(
            args,
            EidosNavigationScope(workshopSubfolderId = 5L, subfolderId = 7L),
        )
        assertEquals(3L, fromArgs)

        val fromScope = EidosNavigationResolver.resolveWorkshopToolSubfolderId(
            buildJsonObject {},
            EidosNavigationScope(workshopSubfolderId = 5L, subfolderId = 7L),
        )
        assertEquals(5L, fromScope)
    }
}
