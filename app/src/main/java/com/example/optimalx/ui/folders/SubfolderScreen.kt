package com.example.optimalx.ui.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.repository.FolderRepository
import kotlinx.coroutines.launch

@Composable
fun SubfolderScreen(
    parentFolderId: Long,
    repository: FolderRepository,
    onSubfolderClick: (Long) -> Unit,
    onQuickNotesInboxClick: (Long) -> Unit,
    onWorkshopSubfolderClick: ((Long) -> Unit)? = null,
    onSystemSubfolderClick: ((Long, String) -> Unit)? = null,
    onBack: () -> Unit,
    onEidosClick: () -> Unit,
    onEidosSectionClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    BackHandler {
        onBack()
    }

    val viewModel: SubfolderViewModel = viewModel(
        key = "subfolder_$parentFolderId",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                SubfolderViewModel(app, parentFolderId)
            }
        }
    )

    val subfolders by viewModel.subfolders.collectAsState()
    val isQuickNotesParent by viewModel.isQuickNotesParent.collectAsState()
    val isWorkshopParent by viewModel.isWorkshopParent.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val layoutMode by viewModel.layoutMode.collectAsState()
    val allParentFolders by viewModel.allParentFolders.collectAsState()
    val pinnedSubfolderIds by viewModel.pinnedSubfolderIds.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onBack) {
                val swipeThresholdPx = 120f
                var consumed = false
                var totalRightDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        consumed = false
                        totalRightDrag = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (dragAmount > 0f) {
                            totalRightDrag += dragAmount
                            if (!consumed && totalRightDrag >= swipeThresholdPx) {
                                consumed = true
                                change.consume()
                                onBack()
                            }
                        } else {
                            totalRightDrag = 0f
                        }
                    },
                    onDragEnd = {
                        consumed = false
                        totalRightDrag = 0f
                    },
                    onDragCancel = {
                        consumed = false
                        totalRightDrag = 0f
                    },
                )
            },
    ) {
        FolderPage(
            locationLabel = "Subfolder",
            folders = subfolders,
            searchQuery = searchQuery,
            searchResults = searchResults,
            onSearchQueryChange = viewModel::onSearchQueryChange,
            sortOrder = sortOrder,
            layoutMode = layoutMode,
            showTrashButton = false,
            showCreateButton = !isQuickNotesParent,
            createFolderLabel = if (isWorkshopParent) "New project" else "New subfolder",
            onFolderClick = { subfolderId ->
                scope.launch {
                    when {
                        isWorkshopParent && onWorkshopSubfolderClick != null ->
                            onWorkshopSubfolderClick(subfolderId)
                        viewModel.shouldOpenQuickNotesInbox(subfolderId) ->
                            onQuickNotesInboxClick(subfolderId)
                        else ->
                            onSubfolderClick(subfolderId)
                    }
                }
            },
            onSystemFolderClick = onSystemSubfolderClick,
            onCreateFolder = viewModel::createSubfolder,
            onRenameFolder = viewModel::renameSubfolder,
            onDeleteFolder = viewModel::deleteSubfolder,
            onSortChange = viewModel::setSortOrder,
            onGridToggle = viewModel::toggleGrid,
            onTrashClick = {},
            onEidosClick = onEidosClick,
            onEidosSectionClick = onEidosSectionClick,
            onSettingsClick = onSettingsClick,
            allParentFolders = allParentFolders,
            currentParentFolderId = parentFolderId,
            onMoveFolder = viewModel::moveSubfolder,
            homePinEnabled = !isQuickNotesParent && !isWorkshopParent,
            pinnedFolderIds = pinnedSubfolderIds,
            onPinToHome = { id, name -> viewModel.pinSubfolder(id, name) },
            onUnpinFromHome = viewModel::unpinSubfolder,
        )
    }
}
