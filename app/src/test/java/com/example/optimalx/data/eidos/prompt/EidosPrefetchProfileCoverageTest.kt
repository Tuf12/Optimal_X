package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.EidosContextLimits
import com.example.optimalx.data.eidos.prefetch.EidosRetrievedContextBlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosPrefetchProfileCoverageTest {

  private val prefetchEnabledProfiles = listOf(
        EidosScopeProfileIds.GENERAL_APP,
        EidosScopeProfileIds.PARENT,
        EidosScopeProfileIds.SUBFOLDER,
        EidosScopeProfileIds.WIDGET_ASK,
        EidosScopeProfileIds.WIDGET_CHAT,
        EidosScopeProfileIds.WORKSHOP_CHAT,
        EidosScopeProfileIds.WORKSHOP_PLAN,
        EidosScopeProfileIds.WORKSHOP_EDIT,
        EidosScopeProfileIds.WORKSHOP_INTAKE,
    )

    private val prefetchDisabledProfiles = listOf(
        EidosScopeProfileIds.QUICK_NOTES_DAY,
        EidosScopeProfileIds.DUMP_EDIT,
        EidosScopeProfileIds.PANEL_GALLERY,
        EidosScopeProfileIds.INTERNAL_MEMORY_ROLLOVER,
    )

    @Test
    fun prefetchEnabledProfiles_includeRetrievalRules() {
        prefetchEnabledProfiles.forEach { profileId ->
            val profile = EidosScopeProfileRegistry.require(profileId)
            val sections = EidosPromptComposer.userFacingPromptSections(profile)
            assertTrue(
                "$profileId should include prefetch retrieval rules",
                sections.any { it.contains(EidosContextLimits.PREFETCH_RETRIEVAL_RULES) },
            )
        }
    }

    @Test
    fun prefetchDisabledProfiles_omitRetrievalRules() {
        prefetchDisabledProfiles.forEach { profileId ->
            val profile = EidosScopeProfileRegistry.require(profileId)
            val prompt = EidosPromptComposer.joinSections(
                EidosPromptComposer.userFacingPromptSections(profile),
            )
            assertFalse(
                "$profileId should not include prefetch retrieval rules",
                prompt.contains(EidosContextLimits.PREFETCH_RETRIEVAL_RULES),
            )
        }
    }

    @Test
    fun widgetRetrievedBlock_placedAfterIdentity() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WIDGET_ASK)
        val retrieved = sampleRetrievedBlock()
        val prompt = EidosPromptComposer.widgetSurfacePromptText(
            profile = profile,
            retrievedContextBlock = retrieved,
        )
        assertBlockAfterIdentity(prompt)
    }

    @Test
    fun workshopRetrievedBlock_placedAfterIdentity() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WORKSHOP_PLAN)
        val retrieved = sampleRetrievedBlock()
        val prompt = EidosPromptComposer.workshopModePromptText(
            profile = profile,
            workshopContext = "Panel Workshop context stub",
            activeParentLine = "Active parent folder: Projects (parentFolderId=1)",
            editorSurfaceHint = null,
            webPanelPageUrl = null,
            retrievedContextBlock = retrieved,
        )
        assertBlockAfterIdentity(prompt)
    }

    private fun sampleRetrievedBlock(): String =
        """
            ${EidosRetrievedContextBlock.SECTION_HEADER}
            [1] Note · My Note (score=0.77)
            Relevant passage
        """.trimIndent()

    private fun assertBlockAfterIdentity(prompt: String) {
        val identityEnd = prompt.indexOf(EidosIdentityPrompt.TEXT) + EidosIdentityPrompt.TEXT.length
        val retrievedStart = prompt.indexOf(EidosRetrievedContextBlock.SECTION_HEADER)
        assertTrue(retrievedStart > identityEnd)
    }
}
