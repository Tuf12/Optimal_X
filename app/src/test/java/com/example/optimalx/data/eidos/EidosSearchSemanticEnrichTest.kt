package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.ConversationScopes
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EidosSearchSemanticEnrichTest {

    @Test
    fun enrichParentScope_addsScopeTypeAndId() {
        val args = buildJsonObject { put("query", JsonPrimitive("budget")) }
        val enriched = EidosSearchSemanticEnrich.enrich(
            args = args,
            currentScopeType = ConversationScopes.PARENT,
            currentSubfolderId = null,
            currentParentFolderId = 42L,
        )
        requireNotNull(enriched)
        assertEquals("parent", enriched["scopeType"]?.toString()?.trim('"'))
        assertEquals("42", enriched["scopeId"]?.toString())
        assertEquals("budget", enriched["query"]?.toString()?.trim('"'))
    }

    @Test
    fun enrichParentScope_preservesExplicitScopeArgs() {
        val args = buildJsonObject {
            put("query", JsonPrimitive("budget"))
            put("scopeType", JsonPrimitive("subfolder"))
            put("scopeId", JsonPrimitive("99"))
        }
        val enriched = EidosSearchSemanticEnrich.enrich(
            args = args,
            currentScopeType = ConversationScopes.PARENT,
            currentSubfolderId = null,
            currentParentFolderId = 42L,
        )
        assertNull(enriched)
    }

    @Test
    fun enrichWorkshopScope_usesLocalFirst() {
        val args = buildJsonObject { put("query", JsonPrimitive("bridge")) }
        val enriched = EidosSearchSemanticEnrich.enrich(
            args = args,
            currentScopeType = ConversationScopes.PANEL_WORKSHOP,
            currentSubfolderId = 7L,
            currentParentFolderId = null,
        )
        requireNotNull(enriched)
        assertEquals("local_first", enriched["scopeType"]?.toString()?.trim('"'))
        assertEquals("7", enriched["scopeId"]?.toString())
    }

    @Test
    fun enrichWorkshopHostLink_subfolderScope_addsTargetId() {
        val hostLink = WorkshopHostLink(
            targetSubfolderId = 55L,
            targetSubfolderName = "Cabinets",
            parentFolderId = 12L,
            parentFolderName = "Kitchen",
            panelTitle = "Budget Panel",
        )
        val args = buildJsonObject {
            put("query", JsonPrimitive("spec"))
            put("scopeType", JsonPrimitive("subfolder"))
        }
        val enriched = EidosSearchSemanticEnrich.enrich(
            args = args,
            currentScopeType = ConversationScopes.PANEL_WORKSHOP,
            currentSubfolderId = 7L,
            currentParentFolderId = null,
            workshopHostLink = hostLink,
        )
        requireNotNull(enriched)
        assertEquals("55", enriched["scopeId"]?.toString())
    }

    @Test
    fun enrichWorkshopHostLink_parentScope_addsParentId() {
        val hostLink = WorkshopHostLink(
            targetSubfolderId = 55L,
            targetSubfolderName = "Cabinets",
            parentFolderId = 12L,
            parentFolderName = "Kitchen",
            panelTitle = "Budget Panel",
        )
        val args = buildJsonObject {
            put("query", JsonPrimitive("notes"))
            put("scopeType", JsonPrimitive("parent"))
        }
        val enriched = EidosSearchSemanticEnrich.enrich(
            args = args,
            currentScopeType = ConversationScopes.PANEL_WORKSHOP,
            currentSubfolderId = 7L,
            currentParentFolderId = null,
            workshopHostLink = hostLink,
        )
        requireNotNull(enriched)
        assertEquals("12", enriched["scopeId"]?.toString())
    }

    @Test
    fun enrichWorkshopHostLink_subfolderWithoutHostLink_returnsNull() {
        val args = buildJsonObject {
            put("query", JsonPrimitive("notes"))
            put("scopeType", JsonPrimitive("subfolder"))
        }
        val enriched = EidosSearchSemanticEnrich.enrich(
            args = args,
            currentScopeType = ConversationScopes.PANEL_WORKSHOP,
            currentSubfolderId = 7L,
            currentParentFolderId = null,
            workshopHostLink = null,
        )
        assertNull(enriched)
    }
}
