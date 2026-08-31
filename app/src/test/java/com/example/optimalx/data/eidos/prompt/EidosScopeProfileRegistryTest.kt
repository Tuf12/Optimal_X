package com.example.optimalx.data.eidos.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosScopeProfileRegistryTest {

    @Test
    fun loopPolicy_workshopChatCappedAtTwo() {
        assertEquals(
            EidosScopeProfile.WORKSHOP_CHAT_MAX_TOOL_ROUNDS,
            EidosScopeProfileRegistry.require(EidosScopeProfileIds.WORKSHOP_CHAT).loopPolicy.maxToolRounds,
        )
    }

    @Test
    fun loopPolicy_workshopBuildModesCappedAtTwelve() {
        listOf(
            EidosScopeProfileIds.WORKSHOP_PLAN,
            EidosScopeProfileIds.WORKSHOP_EDIT,
            EidosScopeProfileIds.WORKSHOP_INTAKE,
        ).forEach { id ->
            assertEquals(
                "Expected build cap for $id",
                EidosScopeProfile.WORKSHOP_BUILD_MAX_TOOL_ROUNDS,
                EidosScopeProfileRegistry.require(id).loopPolicy.maxToolRounds,
            )
        }
    }

    @Test
    fun loopPolicy_nonWorkshopScopesUseGlobalBackstop() {
        listOf(
            EidosScopeProfileIds.GENERAL_APP,
            EidosScopeProfileIds.SUBFOLDER,
            EidosScopeProfileIds.WIDGET_CHAT,
        ).forEach { id ->
            assertEquals(
                "Expected global backstop for $id",
                EidosScopeProfile.GLOBAL_MAX_TOOL_ROUNDS,
                EidosScopeProfileRegistry.require(id).loopPolicy.maxToolRounds,
            )
        }
    }

    @Test
    fun transportHints_defaultEnableIncrementalAndKeepSystemOnContinuation() {
        val profile = EidosScopeProfileRegistry.require(EidosScopeProfileIds.WORKSHOP_EDIT)
        assertTrue(profile.transportHints.incrementalContinuation)
        assertFalse(profile.transportHints.omitSystemOnContinuation)
    }

    @Test
    fun allProfileIds_registeredWithOntology() {
        val expectedIds = listOf(
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
            EidosScopeProfileIds.WORKSHOP_EDIT,
            EidosScopeProfileIds.WORKSHOP_INTAKE,
            EidosScopeProfileIds.INTERNAL_CONTENT_SUMMARY,
            EidosScopeProfileIds.INTERNAL_MEMORY_ROLLOVER,
        )
        expectedIds.forEach { id ->
            val profile = EidosScopeProfileRegistry.require(id)
            assertTrue("Missing ontology for $id", profile.ontologyBlock.isNotBlank())
        }
    }
}
