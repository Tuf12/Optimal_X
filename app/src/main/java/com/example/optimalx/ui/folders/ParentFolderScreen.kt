package com.example.optimalx.ui.folders

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.repository.FolderRepository

@Composable
fun ParentFolderScreen(
    repository: FolderRepository,
    onFolderClick: (Long) -> Unit,
    onSystemFolderClick: ((Long, String) -> Unit)? = null,
    onPinnedPanelsClick: () -> Unit,
    onPinnedDumpEditClick: () -> Unit,
    onPinnedWorkshopClick: () -> Unit,
    onPinnedImageStudioClick: () -> Unit,
    onPinnedQuickNotesClick: () -> Unit,
    onPinnedUserPinClick: (PinnedRowItem.UserPin) -> Unit,
    onTrashClick: () -> Unit,
    onEidosClick: () -> Unit,
    onEidosSectionClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val viewModel: ParentFolderViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                ParentFolderViewModel(app)
            }
        }
    )

    val folders by viewModel.folders.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val layoutMode by viewModel.layoutMode.collectAsState()
    val userPins by viewModel.userPins.collectAsState()
    val pinnedParentIds by viewModel.pinnedParentIds.collectAsState()
    FolderPage(
        locationLabel = "Parent",
        folders = folders,
        searchQuery = searchQuery,
        searchResults = searchResults,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        sortOrder = sortOrder,
        layoutMode = layoutMode,
        showTrashButton = true,
        createFolderLabel = "New folder",
        onFolderClick = onFolderClick,
        onSystemFolderClick = onSystemFolderClick,
        onCreateFolder = viewModel::createFolder,
        onRenameFolder = viewModel::renameFolder,
        onDeleteFolder = viewModel::deleteFolder,
        onSortChange = viewModel::setSortOrder,
        onGridToggle = viewModel::toggleGrid,
        onTrashClick = onTrashClick,
        onEidosClick = onEidosClick,
        onEidosSectionClick = onEidosSectionClick,
        onSettingsClick = onSettingsClick,
        showPinnedRow = true,
        pinnedRowUserPins = userPins,
        onPinnedPanelsClick = onPinnedPanelsClick,
        onPinnedDumpEditClick = onPinnedDumpEditClick,
        onPinnedWorkshopClick = onPinnedWorkshopClick,
        onPinnedImageStudioClick = onPinnedImageStudioClick,
        onPinnedQuickNotesClick = onPinnedQuickNotesClick,
        onPinnedUserPinClick = onPinnedUserPinClick,
        onPinnedUserPinUnpin = { pin -> viewModel.unpinById(pin.pinId) },
        homePinEnabled = true,
        pinnedFolderIds = pinnedParentIds,
        onPinToHome = { id, name -> viewModel.pinFolder(id, name) },
        onUnpinFromHome = viewModel::unpinFolder,
    )
}
