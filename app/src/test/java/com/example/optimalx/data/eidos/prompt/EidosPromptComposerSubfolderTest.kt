package com.example.optimalx.data.eidos.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerSubfolderTest {

    @Test
    fun subfolderPromptText_includesNoteContextAndWriteRules() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.SUBFOLDER)
        val prompt = EidosPromptComposer.subfolderPromptText(
            profile = profile,
            subfolderContext = """
                Current subfolder:
                Name: Kitchen Plans
                subfolderId: 42
                Note: empty.
                Attachments: use list_folder_contents / read_file — not listed inline.
            """.trimIndent(),
            panelBridgeBlock = null,
            activeParentLine = "Active parent folder: Home Reno (parentFolderId=5)",
            editorSurfaceHint = "Editor tab: Note",
            webPanelPageUrl = null,
            dailyMemoryBlock = "Daily Memory (2026-06-22):\nPrefers morning standups.",
        )

        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains(EidosSystemPromptLayers.NOTE_WRITE_RULES))
        assertTrue(prompt.contains("Daily Memory (2026-06-22):"))
        assertTrue(prompt.contains("Prefers morning standups."))
        assertTrue(prompt.contains("subfolderId: 42"))
        assertTrue(prompt.contains("Active parent folder: Home Reno (parentFolderId=5)"))
        assertTrue(prompt.contains("Editor tab: Note"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun subfolderPromptText_includesPanelBridgeWhenPresent() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.SUBFOLDER)
        val prompt = EidosPromptComposer.subfolderPromptText(
            profile = profile,
            subfolderContext = "Current subfolder:\nName: Game\nsubfolderId: 1",
            panelBridgeBlock = "Panel Bridge (ACTIVE — call_panel_function will reach live panel JS):",
            activeParentLine = "Active parent folder: Projects (parentFolderId=2)",
            editorSurfaceHint = null,
            webPanelPageUrl = null,
        )

        assertTrue(prompt.contains("Panel Bridge (ACTIVE"))
        assertFalse(prompt.contains("Optional UI context"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }
}
