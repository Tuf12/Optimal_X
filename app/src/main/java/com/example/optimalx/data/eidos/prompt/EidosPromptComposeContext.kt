package com.example.optimalx.data.eidos.prompt

import android.content.Context
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.PanelBridgeRegistry
import com.example.optimalx.data.eidos.WorkshopDocAlignScope
import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.eidos.WorkshopUpdateSection
import com.example.optimalx.data.semantic.SemanticIndexer

/** Inputs for [EidosPromptComposer.compose] — legacy assembler for not-yet-migrated profiles. */
data class EidosPromptComposeContext(
    val androidContext: Context,
    val database: AppDatabase,
    val scopeType: String?,
    val currentSubfolderId: Long? = null,
    val currentParentFolderId: Long? = null,
    val subfolderEditorSurfaceHint: String? = null,
    val webPanelPageUrl: String? = null,
    val panelBridgeRegistry: PanelBridgeRegistry? = null,
    val workshopOpenFileName: String? = null,
    val workshopOpenFileContent: String? = null,
    val workshopEidosMode: WorkshopEidosMode? = null,
    val workshopProjectPhase: WorkshopProjectPhase? = null,
    val workshopDocAlignScope: WorkshopDocAlignScope? = null,
    val workshopUpdateSection: WorkshopUpdateSection? = null,
    val workshopUserTurns: Int = 0,
    val dumpEditUserMessage: String? = null,
    val userMessage: String? = null,
    val conversationId: Long? = null,
    val semanticIndexer: SemanticIndexer? = null,
    /** Phase-specific rollover instructions; only for [EidosScopeProfileIds.INTERNAL_MEMORY_ROLLOVER]. */
    val internalVolatilePrompt: String? = null,
    val imageStudioHub: Boolean? = null,
    val imageStudioSaveSubfolderId: Long? = null,
    val imageStudioActivePreviewFileName: String? = null,
    val legacyAssembler: suspend () -> String,
)
