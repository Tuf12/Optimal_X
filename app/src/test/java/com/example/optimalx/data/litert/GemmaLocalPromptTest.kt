package com.example.optimalx.data.litert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaLocalPromptTest {

    @Test
    fun compose_toolsEnabled_includesToolRules() {
        val prompt = GemmaLocalPrompt.compose(
            scopeType = null,
            subfolderId = null,
            parentFolderId = null,
            toolsEnabled = true,
        )
        assertTrue(prompt.contains("search_semantic"))
        assertTrue(prompt.contains("write_note"))
        assertFalse(prompt.contains("Chat only"))
    }

    @Test
    fun compose_toolsDisabled_isChatOnly() {
        val prompt = GemmaLocalPrompt.compose(
            scopeType = "subfolder",
            subfolderId = 42L,
            parentFolderId = null,
            toolsEnabled = false,
        )
        assertTrue(prompt.contains("Chat only"))
        assertFalse(prompt.contains("search_semantic"))
        assertFalse(prompt.contains("write_note"))
        assertTrue(prompt.contains("subfolderId=42"))
    }

    @Test
    fun toolDefinitions_respectsToggle() {
        assertTrue(GemmaLocalPolicy.toolDefinitions(toolsEnabled = true).isNotEmpty())
        assertTrue(GemmaLocalPolicy.toolDefinitions(toolsEnabled = false).isEmpty())
    }
}
