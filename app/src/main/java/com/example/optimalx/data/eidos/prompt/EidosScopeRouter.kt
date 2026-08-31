package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.model.ConversationScopes

object EidosScopeRouter {

    fun resolve(context: EidosSendContext): ResolvedEidosScope {
        val profileId = resolveProfileId(context)
        return ResolvedEidosScope(
            profileId = profileId,
            entrySurface = context.entrySurface,
        )
    }

    fun resolveProfileId(context: EidosSendContext): String {
        if (context.entrySurface == EidosEntrySurface.INTERNAL) {
            return resolveInternalProfileId(context.scopeType)
        }

        when (context.scopeType) {
            ConversationScopes.PANEL_GALLERY -> return EidosScopeProfileIds.PANEL_GALLERY
            ConversationScopes.PANEL_RUNNER -> return EidosScopeProfileIds.PANEL_RUNNER
            ConversationScopes.IMAGE_STUDIO -> return EidosScopeProfileIds.IMAGE_STUDIO
            ConversationScopes.PANEL_WORKSHOP -> return resolveWorkshopProfileId(
                mode = context.workshopEidosMode,
                phase = context.workshopProjectPhase,
            )
            ConversationScopes.DUMP_EDIT -> return EidosScopeProfileIds.DUMP_EDIT
            ConversationScopes.WEB_WIDGET -> return EidosScopeProfileIds.WEB_WIDGET
            ConversationScopes.WEB_EDITOR -> return EidosScopeProfileIds.WEB_EDITOR
            ConversationScopes.QUICK_NOTES_DAY -> return EidosScopeProfileIds.QUICK_NOTES_DAY
            ConversationScopes.QUICK_NOTES_ROOT -> return EidosScopeProfileIds.QUICK_NOTES_ROOT
            ConversationScopes.PARENT -> return EidosScopeProfileIds.PARENT
            ConversationScopes.SUBFOLDER -> return EidosScopeProfileIds.SUBFOLDER
            ConversationScopes.GENERAL, null -> return resolveGeneralFamilyProfileId(context.entrySurface)
        }

        return resolveGeneralFamilyProfileId(context.entrySurface)
    }

    private fun resolveGeneralFamilyProfileId(entrySurface: EidosEntrySurface): String = when (entrySurface) {
        EidosEntrySurface.WIDGET_ASK -> EidosScopeProfileIds.WIDGET_ASK
        EidosEntrySurface.WIDGET_CHAT -> EidosScopeProfileIds.WIDGET_CHAT
        EidosEntrySurface.WIDGET_QUICK_NOTE -> EidosScopeProfileIds.QUICK_NOTES_DAY
        EidosEntrySurface.APP_CHAT,
        EidosEntrySurface.BACKGROUND_WORKER,
        -> EidosScopeProfileIds.GENERAL_APP
        EidosEntrySurface.INTERNAL -> EidosScopeProfileIds.GENERAL_APP
    }

    private fun resolveWorkshopProfileId(
        mode: WorkshopEidosMode?,
        phase: WorkshopProjectPhase?,
    ): String {
        if (phase == WorkshopProjectPhase.INTAKE) {
            return EidosScopeProfileIds.WORKSHOP_INTAKE
        }
        return when (WorkshopEidosMode.normalizeToUserChip(mode ?: WorkshopEidosMode.EDIT)) {
            WorkshopEidosMode.CHAT -> EidosScopeProfileIds.WORKSHOP_CHAT
            WorkshopEidosMode.PLAN -> EidosScopeProfileIds.WORKSHOP_PLAN
            else -> EidosScopeProfileIds.WORKSHOP_EDIT
        }
    }

    private fun resolveInternalProfileId(scopeType: String?): String = when (scopeType) {
        "content_summary" -> EidosScopeProfileIds.INTERNAL_CONTENT_SUMMARY
        "rollover" -> EidosScopeProfileIds.INTERNAL_MEMORY_ROLLOVER
        else -> EidosScopeProfileIds.INTERNAL_CONTENT_SUMMARY
    }
}
