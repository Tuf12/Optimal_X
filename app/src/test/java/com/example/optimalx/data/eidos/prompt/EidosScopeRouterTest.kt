package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.model.ConversationScopes
import org.junit.Assert.assertEquals
import org.junit.Test

class EidosScopeRouterTest {

    @Test
    fun resolve_generalApp_fromMainChatEntrySurface() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "hi",
                conversationId = 1L,
                scopeType = ConversationScopes.GENERAL,
                entrySurface = EidosEntrySurface.APP_CHAT,
                subfolderId = null,
                parentFolderId = null,
                conversationHistory = emptyList(),
            ),
        )
        assertEquals(EidosScopeProfileIds.GENERAL_APP, resolved.profileId)
    }

    @Test
    fun resolve_widgetAsk_fromWidgetVoiceQuickAsk() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "hi",
                conversationId = 1L,
                scopeType = ConversationScopes.GENERAL,
                entrySurface = EidosEntrySurface.WIDGET_ASK,
                subfolderId = null,
                parentFolderId = null,
                conversationHistory = emptyList(),
            ),
        )
        assertEquals(EidosScopeProfileIds.WIDGET_ASK, resolved.profileId)
    }

    @Test
    fun resolve_widgetChat_fromWidgetChatUi() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "hi",
                conversationId = 1L,
                scopeType = ConversationScopes.GENERAL,
                entrySurface = EidosEntrySurface.WIDGET_CHAT,
                subfolderId = null,
                parentFolderId = null,
                conversationHistory = emptyList(),
            ),
        )
        assertEquals(EidosScopeProfileIds.WIDGET_CHAT, resolved.profileId)
    }

    @Test
    fun resolve_quickNotesDay_fromWidgetQuickNote() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "capture",
                conversationId = 2L,
                scopeType = ConversationScopes.QUICK_NOTES_DAY,
                entrySurface = EidosEntrySurface.WIDGET_QUICK_NOTE,
                subfolderId = 99L,
                parentFolderId = 1L,
                conversationHistory = emptyList(),
            ),
        )
        assertEquals(EidosScopeProfileIds.QUICK_NOTES_DAY, resolved.profileId)
    }

    @Test
    fun resolve_quickNotesDay_fromAppChat() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "capture",
                conversationId = 2L,
                scopeType = ConversationScopes.QUICK_NOTES_DAY,
                entrySurface = EidosEntrySurface.APP_CHAT,
                subfolderId = 99L,
                parentFolderId = 1L,
                conversationHistory = emptyList(),
            ),
        )
        assertEquals(EidosScopeProfileIds.QUICK_NOTES_DAY, resolved.profileId)
    }

    @Test
    fun resolve_parent_fromAppChat() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "plan",
                conversationId = 4L,
                scopeType = ConversationScopes.PARENT,
                entrySurface = EidosEntrySurface.APP_CHAT,
                subfolderId = null,
                parentFolderId = 5L,
                conversationHistory = emptyList(),
            ),
        )
        assertEquals(EidosScopeProfileIds.PARENT, resolved.profileId)
    }

    @Test
    fun resolve_subfolder_fromAppChat() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "edit note",
                conversationId = 5L,
                scopeType = ConversationScopes.SUBFOLDER,
                entrySurface = EidosEntrySurface.APP_CHAT,
                subfolderId = 10L,
                parentFolderId = 5L,
                conversationHistory = emptyList(),
            ),
        )
        assertEquals(EidosScopeProfileIds.SUBFOLDER, resolved.profileId)
    }

    @Test
    fun resolve_internalContentSummary() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "summarize",
                conversationId = null,
                scopeType = "content_summary",
                entrySurface = EidosEntrySurface.INTERNAL,
                subfolderId = null,
                parentFolderId = null,
                conversationHistory = emptyList(),
            ),
        )
        assertEquals(EidosScopeProfileIds.INTERNAL_CONTENT_SUMMARY, resolved.profileId)
    }

    @Test
    fun resolve_workshopChat_fromWorkshopChatMode() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "discuss",
                conversationId = 3L,
                scopeType = ConversationScopes.PANEL_WORKSHOP,
                entrySurface = EidosEntrySurface.APP_CHAT,
                subfolderId = 10L,
                parentFolderId = null,
                conversationHistory = emptyList(),
                workshopEidosMode = WorkshopEidosMode.CHAT,
                workshopProjectPhase = WorkshopProjectPhase.DESIGN_BUILD,
            ),
        )
        assertEquals(EidosScopeProfileIds.WORKSHOP_CHAT, resolved.profileId)
    }

    @Test
    fun resolve_workshopPlan_fromPlanMode() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "plan scaffold",
                conversationId = 3L,
                scopeType = ConversationScopes.PANEL_WORKSHOP,
                entrySurface = EidosEntrySurface.APP_CHAT,
                subfolderId = 10L,
                parentFolderId = null,
                conversationHistory = emptyList(),
                workshopEidosMode = WorkshopEidosMode.PLAN,
                workshopProjectPhase = WorkshopProjectPhase.DESIGN_BUILD,
            ),
        )
        assertEquals(EidosScopeProfileIds.WORKSHOP_PLAN, resolved.profileId)
    }

    @Test
    fun resolve_imageStudio() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "draft a cover image",
                conversationId = 1L,
                scopeType = ConversationScopes.IMAGE_STUDIO,
                entrySurface = EidosEntrySurface.APP_CHAT,
                subfolderId = 42L,
                parentFolderId = null,
                conversationHistory = emptyList(),
                imageStudioHub = false,
                imageStudioSaveSubfolderId = 42L,
            ),
        )
        assertEquals(EidosScopeProfileIds.IMAGE_STUDIO, resolved.profileId)
    }

    @Test
    fun resolve_internalRollover() {
        val resolved = EidosScopeRouter.resolve(
            EidosSendContext(
                userMessage = "rollover",
                conversationId = null,
                scopeType = "rollover",
                entrySurface = EidosEntrySurface.INTERNAL,
                subfolderId = null,
                parentFolderId = null,
                conversationHistory = emptyList(),
            ),
        )
        assertEquals(EidosScopeProfileIds.INTERNAL_MEMORY_ROLLOVER, resolved.profileId)
    }
}
