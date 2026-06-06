package com.example.optimalx.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.ui.folders.FolderLayoutMode
import com.example.optimalx.ui.folders.PinnedRow
import com.example.optimalx.ui.folders.PinnedRowItem
import com.example.optimalx.ui.folders.components.FolderBottomBar
import com.example.optimalx.ui.folders.components.SortBottomSheet
import com.example.optimalx.ui.gallery.components.PanelGalleryCard
import com.example.optimalx.ui.gallery.components.PanelGalleryContextMenu
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun PanelGalleryScreen(
    eidosViewModel: EidosChatViewModel,
    onBack: () -> Unit,
    onEidosClick: () -> Unit,
    onLaunchPanel: (workshopSubfolderId: Long) -> Unit,
    onOpenDraft: (workshopSubfolderId: Long) -> Unit,
    onOpenWorkshop: () -> Unit,
    onPinnedPanelsClick: () -> Unit,
    onPinnedDumpEditClick: () -> Unit,
    onPinnedWorkshopClick: () -> Unit,
    onPinnedQuickNotesClick: () -> Unit,
    onPinnedUserPinClick: (PinnedRowItem.UserPin) -> Unit,
) {
    val viewModel: PanelGalleryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                PanelGalleryViewModel(app)
            }
        },
    )

    val panels by viewModel.panels.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val layoutMode by viewModel.layoutMode.collectAsState()
    val pinnedPanelIds by viewModel.pinnedPanelIds.collectAsState()
    val userPins by viewModel.userPins.collectAsState()

    var showSortSheet by remember { mutableStateOf(false) }
    var contextMenuPanelId by remember { mutableLongStateOf(-1L) }
    var showContextMenu by remember { mutableStateOf(false) }

    val colors = LocalOptimalXColors.current
    val isGrid = layoutMode != FolderLayoutMode.LIST

    LaunchedEffect(Unit) {
        viewModel.refreshGallery()
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("Back", color = colors.textMid, fontFamily = DmSansFamily)
                }
                Text(
                    text = "Panels",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEidosClick) {
                    Text("Eidos", color = colors.accent, fontFamily = DmSansFamily)
                }
            }
            PinnedRow(
                userPins = userPins,
                onPanelsClick = onPinnedPanelsClick,
                onDumpEditClick = onPinnedDumpEditClick,
                onWorkshopClick = onPinnedWorkshopClick,
                onQuickNotesClick = onPinnedQuickNotesClick,
                onUserPinClick = onPinnedUserPinClick,
                onUserPinLongClick = { pin -> viewModel.unpinById(pin.pinId) },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                panels.isEmpty() -> PanelGalleryEmptyState(onOpenWorkshop = onOpenWorkshop)
                isGrid -> LazyVerticalGrid(
                    columns = GridCells.Fixed(layoutMode.columns),
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(panels, key = { it.subfolderId }) { panel ->
                        GalleryPanelItem(
                            panel = panel,
                            isGrid = true,
                            pinnedPanelIds = pinnedPanelIds,
                            contextMenuPanelId = contextMenuPanelId,
                            showContextMenu = showContextMenu,
                            onLaunchPanel = onLaunchPanel,
                            onOpenDraft = onOpenDraft,
                            onLongClick = {
                                contextMenuPanelId = panel.subfolderId
                                showContextMenu = true
                            },
                            onDismissContextMenu = { showContextMenu = false },
                            onPin = { viewModel.pinPanel(panel.subfolderId, panel.name) },
                            onUnpin = { viewModel.unpinPanel(panel.subfolderId) },
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(panels, key = { it.subfolderId }) { panel ->
                        GalleryPanelItem(
                            panel = panel,
                            isGrid = false,
                            pinnedPanelIds = pinnedPanelIds,
                            contextMenuPanelId = contextMenuPanelId,
                            showContextMenu = showContextMenu,
                            onLaunchPanel = onLaunchPanel,
                            onOpenDraft = onOpenDraft,
                            onLongClick = {
                                contextMenuPanelId = panel.subfolderId
                                showContextMenu = true
                            },
                            onDismissContextMenu = { showContextMenu = false },
                            onPin = { viewModel.pinPanel(panel.subfolderId, panel.name) },
                            onUnpin = { viewModel.unpinPanel(panel.subfolderId) },
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }

        FolderBottomBar(
            isGrid = isGrid,
            showTrashButton = false,
            showCreateButton = false,
            onCreateClick = {},
            onSortClick = { showSortSheet = true },
            onGridToggle = viewModel::toggleGrid,
            onTrashClick = {},
        )
    }

    if (showSortSheet) {
        SortBottomSheet(
            currentSort = sortOrder,
            onSortSelected = viewModel::setSortOrder,
            onDismiss = { showSortSheet = false },
        )
    }
}

@Composable
private fun GalleryPanelItem(
    panel: PanelGalleryItem,
    isGrid: Boolean,
    pinnedPanelIds: Set<Long>,
    contextMenuPanelId: Long,
    showContextMenu: Boolean,
    onLaunchPanel: (Long) -> Unit,
    onOpenDraft: (Long) -> Unit,
    onLongClick: () -> Unit,
    onDismissContextMenu: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        PanelGalleryCard(
            name = panel.name,
            isGrid = isGrid,
            statusBadge = panel.statusBadge,
            onClick = {
                if (panel.isLaunchable) onLaunchPanel(panel.subfolderId)
                else onOpenDraft(panel.subfolderId)
            },
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showContextMenu && contextMenuPanelId == panel.subfolderId) {
            PanelGalleryContextMenu(
                expanded = true,
                isPinnedToHome = pinnedPanelIds.contains(panel.subfolderId),
                onDismiss = onDismissContextMenu,
                onPinToHome = onPin,
                onUnpinFromHome = onUnpin,
            )
        }
    }
}

@Composable
private fun PanelGalleryEmptyState(onOpenWorkshop: () -> Unit) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No panels yet",
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Build a panel in Panel Workshop. Finished panels appear here when the project is complete.",
            color = colors.textDim,
            fontFamily = DmSansFamily,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(
            onClick = onOpenWorkshop,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(
                text = "Open Panel Workshop",
                color = colors.accent,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
