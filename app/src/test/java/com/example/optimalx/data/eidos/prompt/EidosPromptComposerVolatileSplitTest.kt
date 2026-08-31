package com.example.optimalx.data.eidos.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerVolatileSplitTest {

    @Test
    fun volatileSuffixAfterStablePrefix_returnsTailAfterStableBlock() {
        val stable = "Identity\n\nRules"
        val full = "Identity\n\nRules\n\nCurrent subfolder:\nName: Notes"
        val suffix = EidosPromptComposer.volatileSuffixAfterStablePrefix(full, stable)
        assertEquals("Current subfolder:\nName: Notes", suffix)
    }

    @Test
    fun volatileSuffixAfterStablePrefix_emptyWhenNoVolatileTail() {
        val stable = "Identity\n\nRules"
        assertEquals("", EidosPromptComposer.volatileSuffixAfterStablePrefix(stable, stable))
    }

    @Test
    fun subfolderPromptText_stablePrefixIsIdentityOnly() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.SUBFOLDER)
        val stable = EidosPromptComposer.joinSections(EidosPromptComposer.userFacingPromptSections(profile))
        val full = EidosPromptComposer.subfolderPromptText(
            profile = profile,
            subfolderContext = "Current subfolder:\nName: Work\nsubfolderId: 9",
            panelBridgeBlock = null,
            activeParentLine = "Active parent folder: Projects (parentFolderId=1)",
            editorSurfaceHint = null,
            webPanelPageUrl = null,
        )
        assertTrue(full.startsWith(stable))
        val volatile = EidosPromptComposer.volatileSuffixAfterStablePrefix(full, stable)
        assertTrue(volatile.contains("subfolderId: 9"))
        assertTrue(volatile.contains("Note write rules"))
        assertFalse(volatile.contains(EidosIdentityPrompt.TEXT))
    }
}
