package com.example.optimalx.ui.navigation

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.eidos.EidosApiTraceFeature
import com.example.optimalx.data.eidos.ImplementationPlanGate
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.data.repository.FolderRepository
import com.example.optimalx.ui.dumpedit.DumpEditScreen
import com.example.optimalx.ui.editor.EditorScreen
import com.example.optimalx.ui.eidos.ConversationListScreen
import com.example.optimalx.ui.eidos.EidosChatScreen
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.eidos.EidosApiTraceDirectoriesScreen
import com.example.optimalx.ui.eidos.EidosApiTraceRunDetailScreen
import com.example.optimalx.ui.eidos.EidosApiTraceRoutes
import com.example.optimalx.ui.eidos.EidosApiTraceRunsScreen
import com.example.optimalx.ui.eidos.EidosSectionScreen
import com.example.optimalx.ui.eidos.EidosSystemKind
import com.example.optimalx.ui.eidos.EidosSystemFolderScreen
import com.example.optimalx.ui.eidos.EidosSystemNoteScreen
import com.example.optimalx.ui.eidos.LinkedNoteScreen
import com.example.optimalx.ui.eidos.parseSubfolderIdFromLocation
import com.example.optimalx.ui.folders.HomePinType
import com.example.optimalx.ui.folders.ParentFolderScreen
import com.example.optimalx.ui.folders.PinnedRowItem
import com.example.optimalx.ui.folders.SubfolderScreen
import com.example.optimalx.ui.folders.TrashScreen
import com.example.optimalx.ui.gallery.PanelGalleryScreen
import com.example.optimalx.ui.gallery.PanelRunnerScreen
import com.example.optimalx.ui.memorycache.MemoryCacheInboxScreen
import com.example.optimalx.ui.quicknotes.QuickNotesInboxScreen
import com.example.optimalx.ui.reasoning.ReasoningInboxScreen
import com.example.optimalx.ui.settings.SettingsScreen
import com.example.optimalx.ui.workshop.WorkshopEditorScreen
import com.example.optimalx.ui.workshop.review.DiffReviewScreen
import kotlinx.coroutines.launch

object Routes {
    const val PARENT_FOLDERS = "parent_folders"
    const val PANEL_GALLERY = "panel_gallery"
    const val DUMP_EDIT = "dump_edit"
    const val SUBFOLDERS = "subfolders/{parentFolderId}"
    const val EDITOR = "editor/{subfolderId}"
    const val QUICK_NOTES_INBOX = "quick_notes/{subfolderId}"
    const val MEMORY_CACHE_INBOX = "memory_cache/{subfolderId}"
    const val REASONING_INBOX = "reasoning/{subfolderId}"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
    const val OPTIMALX_LINK = "optimalx_link"
    const val EIDOS_SECTION = "eidos_section/{scopeType}/{scopeId}"
    const val EIDOS_FOLDER = "eidos_folder/{kind}"
    const val EIDOS_NOTE = "eidos_note/{kind}/{subfolderId}"
    const val EIDOS_LINKED_NOTE = "eidos_linked_note/{subfolderId}?anchor={anchor}"
    const val WORKSHOP_EDITOR = "workshop_editor/{subfolderId}"
    const val WORKSHOP_DIFF_REVIEW = "workshop_diff_review/{subfolderId}"
    const val PANEL_RUNNER = "panel_runner/{subfolderId}"
    const val CONVERSATION_LIST = "conversation_list/{scopeType}/{scopeId}/{title}"
    const val EIDOS_CHAT = "eidos_chat"
    const val EIDOS_API_TRACE = EidosApiTraceRoutes.DIRECTORIES
    const val EIDOS_API_TRACE_RUNS = EidosApiTraceRoutes.RUNS
    const val EIDOS_API_TRACE_RUN = EidosApiTraceRoutes.RUN_DETAIL

    fun subfolders(parentFolderId: Long) = "subfolders/$parentFolderId"
    fun editor(subfolderId: Long) = "editor/$subfolderId"
    fun quickNotesInbox(subfolderId: Long) = "quick_notes/$subfolderId"
    fun memoryCacheInbox(subfolderId: Long) = "memory_cache/$subfolderId"
    fun reasoningInbox(subfolderId: Long) = "reasoning/$subfolderId"
    fun eidosSection(scopeType: String, scopeId: Long) = "eidos_section/$scopeType/$scopeId"
    fun eidosFolder(kind: EidosSystemKind) = "eidos_folder/${kind.routeValue}"
    fun eidosNote(kind: EidosSystemKind, subfolderId: Long) = "eidos_note/${kind.routeValue}/$subfolderId"
    fun eidosLinkedNote(subfolderId: Long, anchor: String?) =
        "eidos_linked_note/$subfolderId?anchor=${Uri.encode(anchor.orEmpty())}"
    fun workshopEditor(subfolderId: Long) = "workshop_editor/$subfolderId"
    fun workshopDiffReview(subfolderId: Long) = "workshop_diff_review/$subfolderId"
    fun panelRunner(subfolderId: Long) = "panel_runner/$subfolderId"
    fun conversationList(scopeType: String, scopeId: Long, title: String) =
        "conversation_list/$scopeType/$scopeId/${Uri.encode(title)}"
    fun eidosApiTraceRuns(directoryKey: String) = EidosApiTraceRoutes.runs(directoryKey)
    fun eidosApiTraceRun(runId: Long) = EidosApiTraceRoutes.runDetail(runId)
}

@Composable
fun AppNavigation(folderRepository: FolderRepository) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val hasPreviousEntry = navController.previousBackStackEntry != null
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val eidosViewModel: EidosChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                EidosChatViewModel(app as Application)
            }
        }
    )
    val openEidosChat: () -> Unit = {
        navController.navigate(Routes.EIDOS_CHAT) {
            launchSingleTop = true
        }
    }

    // Keep Eidos scope aligned with the visible route. Do not reset while [Routes.EIDOS_CHAT] is
    // showing — the underlying screen is disposed when chat opens (see Panel Runner / Gallery).
    LaunchedEffect(currentRoute, navBackStackEntry) {
        when (currentRoute) {
            Routes.EIDOS_CHAT -> return@LaunchedEffect
            Routes.PARENT_FOLDERS -> eidosViewModel.setGeneralScope()
            Routes.PANEL_GALLERY -> eidosViewModel.setPanelGalleryScope()
            Routes.DUMP_EDIT -> eidosViewModel.setDumpEditScope()
            else -> {
                if (currentRoute?.startsWith("panel_runner/") == true) {
                    navBackStackEntry?.arguments?.getLong("subfolderId")?.let { subfolderId ->
                        eidosViewModel.setPanelRunnerScope(subfolderId)
                    }
                }
            }
        }
    }

    val navigatePinnedUserPin: (PinnedRowItem.UserPin) -> Unit = { pin ->
        scope.launch {
            navigateHomePinTarget(navController, appContext, pin)
        }
    }

    val navigatePinnedWorkshop: () -> Unit = {
        scope.launch {
            folderRepository.getWorkshopParentId()?.let { parentId ->
                navController.navigate(Routes.subfolders(parentId))
            }
        }
    }

    val navigatePinnedQuickNotes: () -> Unit = {
        scope.launch {
            folderRepository.getQuickNotesParentId()?.let { parentId ->
                navController.navigate(Routes.subfolders(parentId))
            }
        }
    }

    BackHandler(
        enabled = currentRoute != null &&
            currentRoute != Routes.PARENT_FOLDERS &&
            !hasPreviousEntry,
    ) {
        navController.navigate(Routes.PARENT_FOLDERS) {
            popUpTo(navController.graph.startDestinationId) { inclusive = false }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = Routes.PARENT_FOLDERS) {

        composable(Routes.PARENT_FOLDERS) {
            ParentFolderScreen(
                repository = folderRepository,
                onFolderClick = { parentFolderId ->
                    navController.navigate(Routes.subfolders(parentFolderId))
                },
                onSystemFolderClick = null,
                onPinnedPanelsClick = { navController.navigate(Routes.PANEL_GALLERY) },
                onPinnedDumpEditClick = { navController.navigate(Routes.DUMP_EDIT) },
                onPinnedWorkshopClick = navigatePinnedWorkshop,
                onPinnedQuickNotesClick = navigatePinnedQuickNotes,
                onPinnedUserPinClick = navigatePinnedUserPin,
                onTrashClick = { navController.navigate(Routes.TRASH) },
                onEidosClick = {
                    eidosViewModel.setGeneralScope()
                    openEidosChat()
                },
                onEidosSectionClick = { navController.navigate(Routes.eidosSection("general", 0L)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.PANEL_GALLERY) {
            PanelGalleryScreen(
                eidosViewModel = eidosViewModel,
                onBack = { navController.popBackStack() },
                onEidosClick = {
                    eidosViewModel.setPanelGalleryScope()
                    openEidosChat()
                },
                onLaunchPanel = { subfolderId ->
                    navController.navigate(Routes.panelRunner(subfolderId))
                },
                onOpenDraft = { subfolderId ->
                    navController.navigate(Routes.workshopEditor(subfolderId))
                },
                onOpenWorkshop = navigatePinnedWorkshop,
                onPinnedPanelsClick = { /* already on gallery */ },
                onPinnedDumpEditClick = { navController.navigate(Routes.DUMP_EDIT) },
                onPinnedWorkshopClick = navigatePinnedWorkshop,
                onPinnedQuickNotesClick = navigatePinnedQuickNotes,
                onPinnedUserPinClick = navigatePinnedUserPin,
            )
        }

        composable(
            route = Routes.PANEL_RUNNER,
            arguments = listOf(navArgument("subfolderId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val subfolderId = backStackEntry.arguments?.getLong("subfolderId") ?: 0L
            PanelRunnerScreen(
                workshopSubfolderId = subfolderId,
                eidosViewModel = eidosViewModel,
                onBack = { navController.popBackStack() },
                onEidosClick = {
                    eidosViewModel.setPanelRunnerScope(subfolderId)
                    openEidosChat()
                },
            )
        }

        composable(Routes.DUMP_EDIT) {
            DumpEditScreen(
                eidosViewModel = eidosViewModel,
                onBack = { navController.popBackStack() },
                onEidosClick = {
                    eidosViewModel.setDumpEditScope()
                    openEidosChat()
                },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onPromotedToEditor = { subfolderId ->
                    navController.navigate(Routes.editor(subfolderId))
                },
            )
        }

        composable(
            route = Routes.SUBFOLDERS,
            arguments = listOf(navArgument("parentFolderId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val parentFolderId = backStackEntry.arguments?.getLong("parentFolderId") ?: 0L
            SubfolderScreen(
                parentFolderId = parentFolderId,
                repository = folderRepository,
                onSubfolderClick = { subfolderId ->
                    navController.navigate(Routes.editor(subfolderId))
                },
                onQuickNotesInboxClick = { subfolderId ->
                    navController.navigate(Routes.quickNotesInbox(subfolderId))
                },
                onWorkshopSubfolderClick = { subfolderId ->
                    navController.navigate(Routes.workshopEditor(subfolderId))
                },
                onSystemSubfolderClick = { subfolderId, name ->
                    when (name) {
                        SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER, "__memory_cache__" -> {
                            navController.navigate(Routes.memoryCacheInbox(subfolderId))
                        }
                        else -> Unit
                    }
                },
                onBack = { navController.popBackStack() },
                onEidosClick = {
                    eidosViewModel.setParentFolderScope(parentFolderId)
                    openEidosChat()
                },
                onEidosSectionClick = { navController.navigate(Routes.eidosSection("parent", parentFolderId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("subfolderId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val subfolderId = backStackEntry.arguments?.getLong("subfolderId") ?: 0L
            EditorScreen(
                subfolderId = subfolderId,
                eidosViewModel = eidosViewModel,
                onBack = { navController.popBackStack() },
                onEidosClick = {
                    eidosViewModel.setSubfolderScope(subfolderId)
                    openEidosChat()
                },
                onEidosSectionClick = { navController.navigate(Routes.eidosSection("subfolder", subfolderId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.WORKSHOP_EDITOR,
            arguments = listOf(navArgument("subfolderId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val subfolderId = backStackEntry.arguments?.getLong("subfolderId") ?: 0L
            WorkshopEditorScreen(
                subfolderId = subfolderId,
                eidosViewModel = eidosViewModel,
                onBack = { navController.popBackStack() },
                onEidosClick = {
                    eidosViewModel.setWorkshopScope(subfolderId)
                    openEidosChat()
                },
                onOpenEidosSheet = openEidosChat,
                onOpenDiffReview = { id ->
                    navController.navigate(Routes.workshopDiffReview(id))
                },
            )
        }

        composable(
            route = Routes.WORKSHOP_DIFF_REVIEW,
            arguments = listOf(navArgument("subfolderId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val subfolderId = backStackEntry.arguments?.getLong("subfolderId") ?: 0L
            val context = LocalContext.current
            DiffReviewScreen(
                subfolderId = subfolderId,
                onBack = { navController.popBackStack() },
                onAllPendingResolved = {
                    navController.popBackStack()
                    if (ImplementationPlanGate.needsAcceptance(context, subfolderId) &&
                        navController.currentDestination?.route == Routes.EIDOS_CHAT
                    ) {
                        eidosViewModel.setWorkshopScope(subfolderId)
                        navController.navigate(Routes.workshopEditor(subfolderId)) {
                            launchSingleTop = true
                        }
                    }
                },
            )
        }

        composable(
            route = Routes.QUICK_NOTES_INBOX,
            arguments = listOf(navArgument("subfolderId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val subfolderId = backStackEntry.arguments?.getLong("subfolderId") ?: 0L
            QuickNotesInboxScreen(
                subfolderId = subfolderId,
                eidosViewModel = eidosViewModel,
                onBack = { navController.popBackStack() },
                onEidosClick = {
                    eidosViewModel.setQuickNotesInboxScope(subfolderId)
                    openEidosChat()
                },
                onEidosSectionClick = { navController.navigate(Routes.eidosSection("subfolder", subfolderId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.MEMORY_CACHE_INBOX,
            arguments = listOf(navArgument("subfolderId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val subfolderId = backStackEntry.arguments?.getLong("subfolderId") ?: 0L
            MemoryCacheInboxScreen(
                subfolderId = subfolderId,
                onBack = { navController.popBackStack() },
                onEidosClick = {
                    eidosViewModel.setSubfolderScope(subfolderId)
                    openEidosChat()
                },
                onEidosSectionClick = { navController.navigate(Routes.eidosSection("subfolder", subfolderId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.REASONING_INBOX,
            arguments = listOf(navArgument("subfolderId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val subfolderId = backStackEntry.arguments?.getLong("subfolderId") ?: 0L
            ReasoningInboxScreen(
                subfolderId = subfolderId,
                onBack = { navController.popBackStack() },
                onEidosClick = {
                    eidosViewModel.setSubfolderScope(subfolderId)
                    openEidosChat()
                },
                onEidosSectionClick = { navController.navigate(Routes.eidosSection("subfolder", subfolderId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.TRASH) {
            TrashScreen(
                repository = folderRepository,
                onBack = { navController.popBackStack() },
                onNavigateToFolder = { subfolderId ->
                    navController.navigate(Routes.editor(subfolderId))
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenOptimalXLink = { navController.navigate(Routes.OPTIMALX_LINK) },
            )
        }

        composable(Routes.OPTIMALX_LINK) {
            com.example.optimalx.ui.link.LinkScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.EIDOS_SECTION,
            arguments = listOf(
                navArgument("scopeType") { type = NavType.StringType },
                navArgument("scopeId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val scopeType = backStackEntry.arguments?.getString("scopeType") ?: "general"
            val scopeId = backStackEntry.arguments?.getLong("scopeId") ?: 0L
            val apiTraceEnabled by EidosApiTraceFeature.isEnabledFlow(LocalContext.current)
                .collectAsState(initial = EidosApiTraceFeature.ENABLED_BY_DEFAULT)
            EidosSectionScreen(
                onBack = { navController.popBackStack() },
                apiTraceEnabled = apiTraceEnabled,
                onOpenApiTrace = { navController.navigate(Routes.EIDOS_API_TRACE) },
                onOpen = { kind ->
                    if (kind == EidosSystemKind.CHATS) {
                        val title = when (scopeType) {
                            "parent" -> "Chats"
                            "subfolder" -> "Chats"
                            else -> "Chats"
                        }
                        navController.navigate(Routes.conversationList(scopeType, scopeId, title))
                    } else {
                        navController.navigate(Routes.eidosFolder(kind))
                    }
                },
            )
        }

        composable(
            route = Routes.CONVERSATION_LIST,
            arguments = listOf(
                navArgument("scopeType") { type = NavType.StringType },
                navArgument("scopeId") { type = NavType.LongType },
                navArgument("title") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val scopeType = backStackEntry.arguments?.getString("scopeType") ?: "general"
            val scopeId = backStackEntry.arguments?.getLong("scopeId") ?: 0L
            val title = backStackEntry.arguments?.getString("title") ?: "Conversations"
            ConversationListScreen(
                scopeType = scopeType,
                scopeId = scopeId,
                title = title,
                onBack = { navController.popBackStack() },
                onOpenConversation = { conversationId ->
                    eidosViewModel.openConversationFromDirectory(conversationId, scopeType, scopeId)
                    openEidosChat()
                },
            )
        }

        composable(Routes.EIDOS_API_TRACE) {
            EidosApiTraceDirectoriesScreen(
                onBack = { navController.popBackStack() },
                onOpenDirectory = { key ->
                    navController.navigate(Routes.eidosApiTraceRuns(key))
                },
            )
        }

        composable(
            route = Routes.EIDOS_API_TRACE_RUNS,
            arguments = listOf(navArgument("directoryKey") { type = NavType.StringType }),
        ) { backStackEntry ->
            val directoryKey = Uri.decode(backStackEntry.arguments?.getString("directoryKey").orEmpty())
            EidosApiTraceRunsScreen(
                directoryKey = directoryKey,
                onBack = { navController.popBackStack() },
                onOpenRun = { runId -> navController.navigate(Routes.eidosApiTraceRun(runId)) },
            )
        }

        composable(
            route = Routes.EIDOS_API_TRACE_RUN,
            arguments = listOf(navArgument("runId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val runId = backStackEntry.arguments?.getLong("runId") ?: 0L
            EidosApiTraceRunDetailScreen(
                runId = runId,
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.EIDOS_FOLDER,
            arguments = listOf(navArgument("kind") { type = NavType.StringType }),
        ) { backStackEntry ->
            val kind = EidosSystemKind.fromRoute(backStackEntry.arguments?.getString("kind"))
            EidosSystemFolderScreen(
                kind = kind,
                onBack = { navController.popBackStack() },
                onOpenNote = { subfolderId ->
                    if (kind == EidosSystemKind.CHATS) {
                        navController.navigate(Routes.editor(subfolderId))
                    } else {
                        navController.navigate(Routes.eidosNote(kind, subfolderId))
                    }
                },
            )
        }

        composable(
            route = Routes.EIDOS_NOTE,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("subfolderId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val kind = EidosSystemKind.fromRoute(backStackEntry.arguments?.getString("kind"))
            val subfolderId = backStackEntry.arguments?.getLong("subfolderId") ?: 0L
            EidosSystemNoteScreen(
                kind = kind,
                subfolderId = subfolderId,
                onBack = { navController.popBackStack() },
                onOpenDeepLink = { location, anchor ->
                    val targetSubfolderId = parseSubfolderIdFromLocation(location)
                    if (targetSubfolderId != null) {
                        navController.navigate(Routes.eidosLinkedNote(targetSubfolderId, anchor))
                    }
                },
            )
        }

        composable(
            route = Routes.EIDOS_LINKED_NOTE,
            arguments = listOf(
                navArgument("subfolderId") { type = NavType.LongType },
                navArgument("anchor") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val subfolderId = backStackEntry.arguments?.getLong("subfolderId") ?: 0L
            val anchor = backStackEntry.arguments?.getString("anchor").orEmpty().ifBlank { null }
            LinkedNoteScreen(
                subfolderId = subfolderId,
                anchor = anchor,
                onBack = { navController.popBackStack() },
                onOpenInEditor = { navController.navigate(Routes.editor(subfolderId)) },
            )
        }
        composable(Routes.EIDOS_CHAT) {
            EidosChatScreen(
                viewModel = eidosViewModel,
                onBack = { navController.popBackStack() },
                onOpenDiffReview = { subId ->
                    navController.navigate(Routes.workshopDiffReview(subId))
                },
            )
        }
    }
}

private fun navigateHomePinTarget(
    navController: NavController,
    context: Context,
    pin: PinnedRowItem.UserPin,
) {
    when (pin.pinType) {
        HomePinType.PARENT ->
            navController.navigate(Routes.subfolders(pin.targetId))
        HomePinType.SUBFOLDER ->
            navController.navigate(Routes.editor(pin.targetId))
        HomePinType.PANEL -> {
            val phase = WorkshopProjectPreferences.getProjectPhase(context, pin.targetId)
            if (phase == WorkshopProjectPhase.COMPLETE) {
                navController.navigate(Routes.panelRunner(pin.targetId))
            } else {
                navController.navigate(Routes.workshopEditor(pin.targetId))
            }
        }
    }
}
