package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.WorkshopDocAlignScope
import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.eidos.model.EidosMessage

/** Runtime facts passed into [EidosScopeRouter] at send time. */
data class EidosSendContext(
    val userMessage: String,
    val conversationId: Long?,
    val scopeType: String?,
    val entrySurface: EidosEntrySurface,
    val subfolderId: Long?,
    val parentFolderId: Long?,
    val conversationHistory: List<EidosMessage>,
    val workshopEidosMode: WorkshopEidosMode? = null,
    val workshopProjectPhase: WorkshopProjectPhase? = null,
    val workshopDocAlignScope: WorkshopDocAlignScope? = null,
    val imageStudioHub: Boolean? = null,
    val imageStudioSaveSubfolderId: Long? = null,
    val imageStudioActivePreviewFileReferenceId: Long? = null,
    val imageStudioActivePreviewFileName: String? = null,
)

data class ResolvedEidosScope(
    val profileId: String,
    val entrySurface: EidosEntrySurface,
)
