package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.model.ConversationScopes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerQuickNotesTest {

    @Test
    fun quickNotesDay_includesCaptureRulesNotNoteWriteRules() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.QUICK_NOTES_DAY)
        val prompt = EidosPromptComposer.quickNotesDayPromptText(
            profile = profile,
            dayContext = "Quick Notes day inbox:\nDate folder: 2026-06-22\nsubfolderId: 42\nEntries on this day: 3",
            editorSurfaceHint = null,
        )

        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains(EidosSystemPromptLayers.QUICK_NOTES_DAY_RULES))
        assertTrue(prompt.contains("write_quick_note"))
        assertFalse(prompt.contains(EidosSystemPromptLayers.NOTE_WRITE_RULES))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun quickNotesRoot_includesDirectoryRules() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.QUICK_NOTES_ROOT)
        val prompt = EidosPromptComposer.quickNotesRootPromptText(
            profile = profile,
            rootContext = "Quick Notes directory:\nparentFolderId: 7",
        )

        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains(EidosSystemPromptLayers.QUICK_NOTES_ROOT_RULES))
        assertTrue(prompt.contains("write_quick_note is not available"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun quickNotesProfiles_haveDistinctLocationBlocks() {
        val day = EidosScopeProfileRegistry.require(EidosScopeProfileIds.QUICK_NOTES_DAY)
        val root = EidosScopeProfileRegistry.require(EidosScopeProfileIds.QUICK_NOTES_ROOT)
        assertNotEquals(day.locationBlock, root.locationBlock)
        assertTrue(day.locationBlock.contains("inbox"))
        assertTrue(root.locationBlock.contains("folder list"))
    }

    @Test
    fun quickNotesDay_isNotNoteEditScope() {
        assertFalse(EidosSystemPromptLayers.isNoteEditScope(ConversationScopes.QUICK_NOTES_DAY))
    }
}
