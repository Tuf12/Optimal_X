package com.example.optimalx.ui.eidos

import android.content.Context
import android.util.Log
import androidx.navigation.NavController
import com.example.optimalx.data.dao.NoteDao
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.eidos.EidosNavigationCodec
import com.example.optimalx.data.eidos.EidosNavigationLookup
import com.example.optimalx.data.eidos.EidosNavigationTarget
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.ui.navigation.NavigationLeaveGuard
import com.example.optimalx.ui.navigation.Routes
import com.example.optimalx.ui.navigation.WorkshopNavigationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EidosNavigation {
    private const val TAG = "EidosNavigation"

    /**
     * @param fromChat When true (chips / optimalx:// from [EidosChatScreen]), skip the leave
     * guard — editors under chat are disposed and a stale dirty handler can hang forever —
     * and pop [Routes.EIDOS_CHAT] so the destination is actually visible.
     */
    suspend fun navigateFromTarget(
        target: EidosNavigationTarget,
        db: AppDatabase,
        navController: NavController,
        appContext: Context,
        eidosViewModel: EidosChatViewModel,
        onUnavailable: (String) -> Unit,
        fromChat: Boolean = false,
    ) {
        val normalized = EidosNavigationCodec.normalizeTarget(target) ?: run {
            onUnavailable("Invalid navigation target.")
            return
        }
        if (!fromChat && !NavigationLeaveGuard.confirmLeave()) return

        try {
            when (normalized.kind) {
                "dump_edit" -> {
                    eidosViewModel.setDumpEditScope()
                    go(navController, Routes.DUMP_EDIT)
                }
                "parent" -> openParent(normalized, db, navController, eidosViewModel, onUnavailable)
                "workshop", "workshop_file" -> openWorkshop(
                    normalized,
                    db,
                    navController,
                    appContext,
                    eidosViewModel,
                    onUnavailable,
                )
                "quick_notes" -> openQuickNotes(
                    normalized,
                    db,
                    navController,
                    eidosViewModel,
                    onUnavailable,
                )
                else -> openNoteLike(
                    normalized,
                    db,
                    navController,
                    appContext,
                    eidosViewModel,
                    onUnavailable,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "navigateFromTarget failed kind=${normalized.kind}", t)
            onUnavailable("Could not open ${normalized.label}.")
        }
    }

    suspend fun navigateFromOptimalxUri(
        uri: String,
        linkLabel: String?,
        db: AppDatabase,
        navController: NavController,
        appContext: Context,
        eidosViewModel: EidosChatViewModel,
        onUnavailable: (String) -> Unit,
        fromChat: Boolean = false,
    ): Boolean {
        val target = EidosNavigationCodec.parseOptimalxUri(uri) ?: return false
        val withLabel = if (!linkLabel.isNullOrBlank()) {
            target.copy(label = linkLabel.trim())
        } else {
            target
        }
        navigateFromTarget(
            withLabel,
            db,
            navController,
            appContext,
            eidosViewModel,
            onUnavailable,
            fromChat = fromChat,
        )
        return true
    }

    private suspend fun openParent(
        target: EidosNavigationTarget,
        db: AppDatabase,
        navController: NavController,
        eidosViewModel: EidosChatViewModel,
        onUnavailable: (String) -> Unit,
    ) {
        val parentFolderId = target.parentFolderId ?: return onUnavailable("Folder unavailable.")
        val lookup = EidosNavigationLookup(db.subfolderDao(), db.parentFolderDao())
        val parent = lookup.parentById(parentFolderId)
        if (parent == null) {
            onUnavailable("That folder is no longer available.")
            return
        }
        eidosViewModel.setParentFolderScope(parentFolderId)
        go(navController, Routes.subfolders(parentFolderId))
    }

    private suspend fun openWorkshop(
        target: EidosNavigationTarget,
        db: AppDatabase,
        navController: NavController,
        appContext: Context,
        eidosViewModel: EidosChatViewModel,
        onUnavailable: (String) -> Unit,
    ) {
        val subfolderId = target.subfolderId ?: return onUnavailable("Workshop unavailable.")
        val lookup = EidosNavigationLookup(db.subfolderDao(), db.parentFolderDao())
        if (lookup.subfolderById(subfolderId) == null) {
            onUnavailable("That workshop is no longer available.")
            return
        }
        eidosViewModel.setWorkshopScope(subfolderId)
        if (target.kind == "workshop_file") {
            WorkshopNavigationState.pendingSelectPath = target.path
        }
        val phase = WorkshopProjectPreferences.getProjectPhase(appContext, subfolderId)
        val route = if (phase == WorkshopProjectPhase.COMPLETE) {
            Routes.panelRunner(subfolderId)
        } else {
            Routes.workshopEditor(subfolderId)
        }
        go(navController, route)
    }

    private suspend fun openQuickNotes(
        target: EidosNavigationTarget,
        db: AppDatabase,
        navController: NavController,
        eidosViewModel: EidosChatViewModel,
        onUnavailable: (String) -> Unit,
    ) {
        val subfolderId = target.subfolderId ?: return onUnavailable("Quick note unavailable.")
        if (!noteAvailable(db.noteDao(), subfolderId, onUnavailable, requireNoteRow = false)) return
        eidosViewModel.setQuickNotesInboxScope(subfolderId)
        go(navController, Routes.quickNotesInbox(subfolderId))
    }

    private suspend fun openNoteLike(
        target: EidosNavigationTarget,
        db: AppDatabase,
        navController: NavController,
        appContext: Context,
        eidosViewModel: EidosChatViewModel,
        onUnavailable: (String) -> Unit,
    ) {
        val subfolderId = target.subfolderId ?: return onUnavailable("Note unavailable.")
        val lookup = EidosNavigationLookup(db.subfolderDao(), db.parentFolderDao())
        val ctx = lookup.subfolderById(subfolderId)
        if (ctx == null) {
            onUnavailable("That note is no longer available.")
            return
        }
        // create_subfolder chips may fire before a notes row exists — still open the editor.
        val requireNote = target.kind != "subfolder"
        if (!noteAvailable(db.noteDao(), subfolderId, onUnavailable, requireNoteRow = requireNote)) return

        when (ctx.parentName) {
            SystemFolderNames.QUICK_NOTES -> {
                eidosViewModel.setQuickNotesInboxScope(subfolderId)
                go(navController, Routes.quickNotesInbox(subfolderId))
            }
            SystemFolderNames.PANEL_WORKSHOP -> {
                eidosViewModel.setWorkshopScope(subfolderId)
                val phase = WorkshopProjectPreferences.getProjectPhase(appContext, subfolderId)
                val route = if (phase == WorkshopProjectPhase.COMPLETE) {
                    Routes.panelRunner(subfolderId)
                } else {
                    Routes.workshopEditor(subfolderId)
                }
                go(navController, route)
            }
            else -> {
                eidosViewModel.setSubfolderScope(subfolderId)
                go(navController, Routes.editor(subfolderId))
            }
        }
    }

    private suspend fun noteAvailable(
        noteDao: NoteDao,
        subfolderId: Long,
        onUnavailable: (String) -> Unit,
        requireNoteRow: Boolean,
    ): Boolean {
        val note = noteDao.getBySubfolderOnce(subfolderId)
        if (note == null) {
            if (!requireNoteRow) return true
            onUnavailable("That note is no longer available.")
            return false
        }
        if (note.aiBlind) {
            onUnavailable("Note unavailable.")
            return false
        }
        return true
    }

    private suspend fun go(navController: NavController, route: String) {
        withContext(Dispatchers.Main.immediate) {
            // Always drop Eidos chat if present so the destination is visible (widget deep-link
            // and in-app chips). No-op when chat is not on the stack.
            navController.navigate(route) {
                popUpTo(Routes.EIDOS_CHAT) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}
