package com.example.optimalx.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.repository.SearchResult
import com.example.optimalx.ui.folders.components.CreateFolderDialog
import com.example.optimalx.ui.folders.components.FolderBottomBar
import com.example.optimalx.ui.folders.components.FolderCard
import com.example.optimalx.ui.folders.components.FolderContextMenu
import com.example.optimalx.ui.folders.components.FolderTopBar
import com.example.optimalx.ui.folders.components.MoveFolderDialog
import com.example.optimalx.ui.folders.components.RenameFolderDialog
import com.example.optimalx.ui.folders.components.SortBottomSheet
import com.example.optimalx.ui.folders.systemFolderBrandingForName
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

/**
 * Shared layout used by both ParentFolderScreen and SubfolderScreen.
 * All data is passed in — this composable has no direct ViewModel dependency.
 */
@Composable
fun FolderPage(
    locationLabel: String,
    folders: List<FolderDisplayItem>,
    searchQuery: String,
    searchResults: List<SearchResult>,
    onSearchQueryChange: (String) -> Unit,
    sortOrder: SortOrder,
    layoutMode: FolderLayoutMode,
    showTrashButton: Boolean,
    showCreateButton: Boolean = true,
    createFolderLabel: String,
    onFolderClick: (Long) -> Unit,
    onSystemFolderClick: ((Long, String) -> Unit)? = null,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (Long, String) -> Unit,
    onDeleteFolder: (Long) -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onGridToggle: () -> Unit,
    onTrashClick: () -> Unit,
    onEidosClick: () -> Unit,
    onEidosSectionClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    showPinnedRow: Boolean = false,
    pinnedRowUserPins: List<PinnedRowItem.UserPin> = emptyList(),
    onPinnedPanelsClick: (() -> Unit)? = null,
    onPinnedDumpEditClick: (() -> Unit)? = null,
    onPinnedWorkshopClick: (() -> Unit)? = null,
    onPinnedQuickNotesClick: (() -> Unit)? = null,
    onPinnedUserPinClick: ((PinnedRowItem.UserPin) -> Unit)? = null,
    onPinnedUserPinUnpin: ((PinnedRowItem.UserPin) -> Unit)? = null,
    homePinEnabled: Boolean = false,
    pinnedFolderIds: Set<Long> = emptySet(),
    onPinToHome: ((folderId: Long, name: String) -> Unit)? = null,
    onUnpinFromHome: ((folderId: Long) -> Unit)? = null,
    // Move (subfolders only)
    allParentFolders: List<ParentFolder> = emptyList(),
    currentParentFolderId: Long = 0L,
    onMoveFolder: ((subfolderId: Long, newParentId: Long) -> Unit)? = null,
) {
    val colors = LocalOptimalXColors.current
    val isGrid = layoutMode != FolderLayoutMode.LIST

    // Dialog / sheet state
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var contextMenuFolderId by remember { mutableLongStateOf(-1L) }
    var contextMenuFolderName by remember { mutableStateOf("") }
    var showContextMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableLongStateOf(-1L) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // Status bar padding + top bar
        Column(modifier = Modifier.statusBarsPadding()) {
            FolderTopBar(
                locationLabel = locationLabel,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                onEidosClick = onEidosClick,
                onEidosSectionClick = onEidosSectionClick,
                onSettingsClick = onSettingsClick,
            )
            if (
                showPinnedRow &&
                onPinnedPanelsClick != null &&
                onPinnedDumpEditClick != null &&
                onPinnedWorkshopClick != null &&
                onPinnedQuickNotesClick != null
            ) {
                PinnedRow(
                    userPins = pinnedRowUserPins,
                    onPanelsClick = onPinnedPanelsClick,
                    onDumpEditClick = onPinnedDumpEditClick,
                    onWorkshopClick = onPinnedWorkshopClick,
                    onQuickNotesClick = onPinnedQuickNotesClick,
                    onUserPinClick = { pin -> onPinnedUserPinClick?.invoke(pin) },
                    onUserPinLongClick = { pin -> onPinnedUserPinUnpin?.invoke(pin) },
                )
            }
        }

        // Content area
        Box(modifier = Modifier.weight(1f)) {
            when {
                searchQuery.isNotBlank() -> SearchResultsList(
                    results = searchResults,
                    onResultClick = { result ->
                        if (result.isSubfolder) onFolderClick(result.id)
                        else onFolderClick(result.id)
                    },
                )
                folders.isEmpty() -> EmptyState(createFolderLabel)
                isGrid -> LazyVerticalGrid(
                    columns = GridCells.Fixed(layoutMode.columns),
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(folders, key = { it.id }) { folder ->
                        val clickHandler = {
                            if (folder.isSystem && onSystemFolderClick != null) {
                                onSystemFolderClick(folder.id, folder.name)
                            } else {
                                onFolderClick(folder.id)
                            }
                        }
                        Box {
                            FolderCard(
                                name = folder.name,
                                isGrid = true,
                                onClick = clickHandler,
                                onLongClick = {
                                    if (!folder.isSystem) {
                                        contextMenuFolderId = folder.id
                                        contextMenuFolderName = folder.name
                                        showContextMenu = true
                                    }
                                },
                                modifier = Modifier.padding(6.dp),
                                systemBranding = if (folder.isSystem) {
                                    systemFolderBrandingForName(folder.name)
                                } else {
                                    null
                                },
                            )
                            if (showContextMenu && contextMenuFolderId == folder.id) {
                                FolderCardContextMenu(
                                    showMove = onMoveFolder != null,
                                    homePinEnabled = homePinEnabled,
                                    isPinnedToHome = pinnedFolderIds.contains(folder.id),
                                    onDismiss = { showContextMenu = false },
                                    onRename = { showRenameDialog = true },
                                    onDelete = {
                                        pendingDeleteId = folder.id
                                        onDeleteFolder(folder.id)
                                    },
                                    onMove = if (onMoveFolder != null) {{ showMoveDialog = true }} else null,
                                    onPinToHome = if (onPinToHome != null) {
                                        { onPinToHome(folder.id, folder.name) }
                                    } else {
                                        null
                                    },
                                    onUnpinFromHome = if (onUnpinFromHome != null) {
                                        { onUnpinFromHome(folder.id) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(folders, key = { it.id }) { folder ->
                        val clickHandler = {
                            if (folder.isSystem && onSystemFolderClick != null) {
                                onSystemFolderClick(folder.id, folder.name)
                            } else {
                                onFolderClick(folder.id)
                            }
                        }
                        Box {
                            FolderCard(
                                name = folder.name,
                                isGrid = false,
                                onClick = clickHandler,
                                onLongClick = {
                                    if (!folder.isSystem) {
                                        contextMenuFolderId = folder.id
                                        contextMenuFolderName = folder.name
                                        showContextMenu = true
                                    }
                                },
                                modifier = Modifier.padding(vertical = 4.dp),
                                systemBranding = if (folder.isSystem) {
                                    systemFolderBrandingForName(folder.name)
                                } else {
                                    null
                                },
                            )
                            if (showContextMenu && contextMenuFolderId == folder.id) {
                                FolderCardContextMenu(
                                    showMove = onMoveFolder != null,
                                    homePinEnabled = homePinEnabled,
                                    isPinnedToHome = pinnedFolderIds.contains(folder.id),
                                    onDismiss = { showContextMenu = false },
                                    onRename = { showRenameDialog = true },
                                    onDelete = { onDeleteFolder(folder.id) },
                                    onMove = if (onMoveFolder != null) {{ showMoveDialog = true }} else null,
                                    onPinToHome = if (onPinToHome != null) {
                                        { onPinToHome(folder.id, folder.name) }
                                    } else {
                                        null
                                    },
                                    onUnpinFromHome = if (onUnpinFromHome != null) {
                                        { onUnpinFromHome(folder.id) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom bar
        FolderBottomBar(
            isGrid = isGrid,
            showTrashButton = showTrashButton,
            showCreateButton = showCreateButton,
            onCreateClick = { showCreateDialog = true },
            onSortClick = { showSortSheet = true },
            onGridToggle = onGridToggle,
            onTrashClick = onTrashClick,
        )
    }

    // Dialogs and sheets
    if (showCreateDialog) {
        CreateFolderDialog(
            label = createFolderLabel,
            onConfirm = { name -> onCreateFolder(name); showCreateDialog = false },
            onDismiss = { showCreateDialog = false },
        )
    }

    if (showRenameDialog && contextMenuFolderId != -1L) {
        RenameFolderDialog(
            currentName = contextMenuFolderName,
            onConfirm = { newName -> onRenameFolder(contextMenuFolderId, newName); showRenameDialog = false },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showMoveDialog && contextMenuFolderId != -1L && onMoveFolder != null) {
        MoveFolderDialog(
            currentParentId = currentParentFolderId,
            parentFolders = allParentFolders,
            onConfirm = { newParentId ->
                onMoveFolder(contextMenuFolderId, newParentId)
                showMoveDialog = false
            },
            onDismiss = { showMoveDialog = false },
        )
    }

    if (showSortSheet) {
        SortBottomSheet(
            currentSort = sortOrder,
            onSortSelected = onSortChange,
            onDismiss = { showSortSheet = false },
        )
    }
}

@Composable
private fun FolderCardContextMenu(
    showMove: Boolean,
    homePinEnabled: Boolean,
    isPinnedToHome: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (() -> Unit)?,
    onPinToHome: (() -> Unit)?,
    onUnpinFromHome: (() -> Unit)?,
) {
    FolderContextMenu(
        expanded = true,
        showMove = showMove,
        showPinToHome = homePinEnabled,
        isPinnedToHome = isPinnedToHome,
        onDismiss = onDismiss,
        onRename = onRename,
        onDelete = onDelete,
        onMove = onMove,
        onPinToHome = onPinToHome,
        onUnpinFromHome = onUnpinFromHome,
    )
}

@Composable
private fun EmptyState(label: String) {
    val colors = LocalOptimalXColors.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "No ${label.lowercase()}s yet",
            color = colors.textDim,
            fontFamily = DmSansFamily,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun SearchResultsList(
    results: List<SearchResult>,
    onResultClick: (SearchResult) -> Unit,
) {
    val colors = LocalOptimalXColors.current
    if (results.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No results", color = colors.textDim, fontFamily = DmSansFamily, fontSize = 15.sp)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        items(results, key = { "${it.isSubfolder}_${it.id}" }) { result ->
            FolderCard(
                name = if (result.isSubfolder && result.parentFolderName.isNotEmpty())
                    "${result.parentFolderName} / ${result.name}"
                else result.name,
                isGrid = false,
                onClick = { onResultClick(result) },
                onLongClick = {},
                modifier = Modifier.padding(vertical = 4.dp),
            )
            if (result.noteSnippet.isNotEmpty()) {
                Text(
                    text = result.noteSnippet,
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 12.sp,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
