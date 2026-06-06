package com.example.optimalx.ui.workshop

import android.content.Context
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.WorkshopDocAlignGate
import com.example.optimalx.data.eidos.WorkshopDocAlignScope
import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.panel.PanelReleaseStore
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.data.revision.PENDING_ITEM_STATUS_PENDING
import com.example.optimalx.data.revision.SCOPE_WORKSHOP_PROJECT

/**
 * Finishes workshop Update → Complete transitions when Eidos sends complete.
 * Must run from [com.example.optimalx.ui.eidos.EidosChatViewModel] — not from
 * [WorkshopEditorScreen], which is disposed while the user is on the chat route.
 */
object WorkshopUpdateCompletion {

    suspend fun onWorkshopSendFinished(
        context: Context,
        db: AppDatabase,
        subfolderId: Long,
        docAlignScope: WorkshopDocAlignScope?,
        sendSucceeded: Boolean,
    ) {
        if (docAlignScope != null) {
            WorkshopProjectPreferences.setPendingUpdateAwaitingAlign(context, subfolderId, false)
            if (sendSucceeded) {
                WorkshopProjectPreferences.setPendingUpdateDocAlignDone(context, subfolderId, true)
                WorkshopDocAlignGate.recordAlignFingerprint(context, db, subfolderId, docAlignScope)
            }
        } else if (!sendSucceeded &&
            WorkshopProjectPreferences.isPendingUpdateAwaitingAlign(context, subfolderId)
        ) {
            WorkshopProjectPreferences.setPendingUpdateAwaitingAlign(context, subfolderId, false)
        }
        tryFinishPendingUpdate(context, db, subfolderId)
    }

    suspend fun tryFinishPendingUpdate(
        context: Context,
        db: AppDatabase,
        subfolderId: Long,
    ) {
        if (!WorkshopProjectPreferences.isPendingFinishUpdate(context, subfolderId)) return
        if (WorkshopProjectPreferences.getProjectPhase(context, subfolderId) != WorkshopProjectPhase.UPDATE) {
            clearPendingFinishFlags(context, subfolderId)
            return
        }
        if (WorkshopProjectPreferences.isPendingUpdateAwaitingAlign(context, subfolderId)) return
        val pendingChanges = countWorkshopPendingChanges(db, subfolderId)
        if (pendingChanges > 0) return
        if (!WorkshopProjectPreferences.isPendingUpdateDocAlignDone(context, subfolderId)) return
        finishUpdateCycleToComplete(context, db, subfolderId)
    }

    /**
     * Diffs reviewed and doc align finished — leave maintenance and return to Complete.
     * Next edit session starts from **Update** on the Complete phase again.
     */
    suspend fun finishUpdateCycleToComplete(context: Context, db: AppDatabase, subfolderId: Long) {
        exitMaintenanceToComplete(context, subfolderId)
        PanelReleaseStore.publishFromWorkshop(context, db, subfolderId)
    }

    /** User leaves maintenance — return to Complete (**Update** entry point). */
    fun exitMaintenanceToComplete(context: Context, subfolderId: Long) {
        clearPendingFinishFlags(context, subfolderId)
        WorkshopProjectPreferences.setUpdateSection(context, subfolderId, null)
        WorkshopProjectPreferences.setProjectPhase(context, subfolderId, WorkshopProjectPhase.COMPLETE)
        WorkshopProjectPreferences.setEidosModeOverride(context, subfolderId, WorkshopEidosMode.EDIT)
    }

    private suspend fun countWorkshopPendingChanges(db: AppDatabase, subfolderId: Long): Int {
        val set = db.pendingChangeDao().findOpenSetForScope(SCOPE_WORKSHOP_PROJECT, subfolderId)
            ?: return 0
        return db.pendingChangeDao().listItems(set.id)
            .count { it.status == PENDING_ITEM_STATUS_PENDING }
    }

    private fun clearPendingFinishFlags(context: Context, subfolderId: Long) {
        WorkshopProjectPreferences.setPendingFinishUpdate(context, subfolderId, false)
        WorkshopProjectPreferences.setPendingUpdateAwaitingAlign(context, subfolderId, false)
        WorkshopProjectPreferences.setPendingUpdateDocAlignDone(context, subfolderId, false)
    }

}
