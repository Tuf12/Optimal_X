package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.prompt.EidosScopeProfileIds
import com.example.optimalx.data.eidos.prompt.EidosScopeProfileRegistry
import com.example.optimalx.data.model.ConversationScopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosToolProfileTest {

    private val catalogNames = EidosToolCatalog.all.map { it.name }.toSet()

    private val internalOnlyTools = setOf(
        "read_daily_memory",
        "read_long_term_memory",
        "read_journal",
        "read_log",
        "prune_long_term_memory",
        "write_journal_entry",
        "search_chat_history",
    )

    private val userFacingProfileIds = listOf(
        EidosScopeProfileIds.GENERAL_APP,
        EidosScopeProfileIds.PARENT,
        EidosScopeProfileIds.SUBFOLDER,
        EidosScopeProfileIds.WIDGET_ASK,
        EidosScopeProfileIds.WIDGET_CHAT,
        EidosScopeProfileIds.QUICK_NOTES_DAY,
        EidosScopeProfileIds.QUICK_NOTES_ROOT,
        EidosScopeProfileIds.DUMP_EDIT,
        EidosScopeProfileIds.WEB_WIDGET,
        EidosScopeProfileIds.WEB_EDITOR,
        EidosScopeProfileIds.PANEL_GALLERY,
        EidosScopeProfileIds.PANEL_RUNNER,
        EidosScopeProfileIds.WORKSHOP_CHAT,
        EidosScopeProfileIds.WORKSHOP_PLAN,
        EidosScopeProfileIds.WORKSHOP_INTAKE,
    )

    @Test
    fun registryToolNames_existInCatalog() {
        EidosScopeProfileRegistry.allProfiles().forEach { profile ->
            profile.toolNames.forEach { name ->
                assertTrue("$name in ${profile.id} missing from catalog", name in catalogNames)
            }
        }
    }

    @Test
    fun userFacingProfiles_excludeInternalMemoryTools() {
        userFacingProfileIds.forEach { profileId ->
            val names = EidosToolCatalog.toolsForProfile(
                profileId = profileId,
                scopeType = null,
                workshopMode = null,
                workshopPhase = null,
            ).map { it.name }.toSet()
            assertTrue(
                "$profileId exposes internal-only tools: ${names.intersect(internalOnlyTools)}",
                names.none { it in internalOnlyTools },
            )
        }
    }

    @Test
    fun widgetAsk_cannotCallWriteQuickNote() {
        val names = toolNames(EidosScopeProfileIds.WIDGET_ASK)
        assertFalse(names.contains("write_quick_note"))
    }

    @Test
    fun widgetChat_cannotCallWriteQuickNote() {
        val names = toolNames(EidosScopeProfileIds.WIDGET_CHAT)
        assertFalse(names.contains("write_quick_note"))
    }

    @Test
    fun quickNotesDay_onlyProfileWithWriteQuickNoteAmongUserChat() {
        val quickNoteProfiles = userFacingProfileIds.filter { profileId ->
            toolNames(profileId).contains("write_quick_note")
        }
        assertEquals(listOf(EidosScopeProfileIds.QUICK_NOTES_DAY), quickNoteProfiles)
    }

    @Test
    fun generalApp_isNarrowerThanLegacyGeneralScopedChat() {
        val legacy = EidosToolCatalog.toolsForScopedChat(ConversationScopes.GENERAL)
            .map { it.name }
            .toSet()
        val profile = toolNames(EidosScopeProfileIds.GENERAL_APP)
        assertFalse(profile.contains("write_quick_note"))
        assertFalse(profile.contains("describe_image"))
        assertTrue(legacy.contains("write_quick_note"))
        assertTrue(profile.contains("rename_folder"))
        assertTrue(profile.contains("move_to_trash"))
    }

    @Test
    fun widgetSurfaces_omitFolderAdminTools() {
        listOf(EidosScopeProfileIds.WIDGET_ASK, EidosScopeProfileIds.WIDGET_CHAT).forEach { profileId ->
            val names = toolNames(profileId)
            assertFalse(names.contains("rename_folder"))
            assertFalse(names.contains("move_to_trash"))
            assertFalse(names.contains("list_folder_contents"))
        }
    }

    @Test
    fun workshopChat_includesReadFile() {
        val names = toolNames(EidosScopeProfileIds.WORKSHOP_CHAT)
        assertTrue(names.contains("read_file"))
        assertTrue(names.contains("workshop_read_file"))
        assertFalse(names.contains("workshop_write_file"))
    }

    @Test
    fun internalProfiles_exposeNoTools() {
        listOf(
            EidosScopeProfileIds.INTERNAL_CONTENT_SUMMARY,
            EidosScopeProfileIds.INTERNAL_MEMORY_ROLLOVER,
        ).forEach { profileId ->
            assertTrue(
                toolNames(profileId).isEmpty(),
            )
        }
    }

    private fun toolNames(profileId: String): Set<String> =
        EidosToolCatalog.toolsForProfile(
            profileId = profileId,
            scopeType = null,
            workshopMode = null,
            workshopPhase = null,
        ).map { it.name }.toSet()
}
