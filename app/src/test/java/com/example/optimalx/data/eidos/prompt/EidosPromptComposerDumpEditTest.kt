package com.example.optimalx.data.eidos.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPromptComposerDumpEditTest {

    @Test
    fun dumpEditPromptText_includesRegistryOntologyLocationAndRules() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.DUMP_EDIT)
        val prompt = EidosPromptComposer.dumpEditPromptText(
            profile = profile,
            bufferContext = "Empty — nothing to read yet.",
        )

        assertTrue(prompt.contains(EidosIdentityPrompt.TEXT))
        assertTrue(prompt.contains(profile.ontologyBlock))
        assertTrue(prompt.contains(profile.locationBlock))
        assertTrue(prompt.contains(EidosSystemPromptLayers.DUMP_EDIT_RULES))
        assertTrue(prompt.contains("Empty — nothing to read yet."))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun dumpEditPromptText_omitsLegacyScratchBufferHeader() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.DUMP_EDIT)
        val prompt = EidosPromptComposer.dumpEditPromptText(
            profile = profile,
            bufferContext = "Full buffer (12 chars):\nHello world!",
        )
        assertFalse(prompt.contains("DumpEdit scratch buffer:"))
        EidosPromptComposerAssertions.assertNoLegacyUniversalPromptBlocks(prompt)
    }

    @Test
    fun dumpEditProfile_hasLocationBlock() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.DUMP_EDIT)
        assertTrue(profile.locationBlock.contains("DumpEdit"))
    }
}
