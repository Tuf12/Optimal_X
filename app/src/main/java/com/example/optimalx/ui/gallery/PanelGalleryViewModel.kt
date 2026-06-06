package com.example.optimalx.ui.gallery

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.panel.PanelReleaseStore
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.data.preferences.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.optimalx.data.repository.FolderRepository
import com.example.optimalx.data.repository.HomePinRepository
import com.example.optimalx.ui.folders.FolderLayoutMode
import com.example.optimalx.ui.folders.HomePinType
import com.example.optimalx.ui.folders.PinnedRowItem
import com.example.optimalx.ui.folders.SortOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PanelGalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef = app as OptimalXApplication
    private val repo: FolderRepository = appRef.folderRepository
    private val homePinRepo: HomePinRepository = appRef.homePinRepository
    private val ctx = app.applicationContext

    private val _sortOrder = MutableStateFlow(SortOrder.NAME_ASC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    val layoutMode: StateFlow<FolderLayoutMode> = ctx.settingsDataStore.data
        .map { prefs -> FolderLayoutMode.fromKey(prefs[SettingsKeys.FOLDER_LAYOUT] ?: SettingsDefaults.FOLDER_LAYOUT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderLayoutMode.GRID_2)

    private val _workshopParentId = MutableStateFlow<Long?>(null)
    private val _galleryRefresh = MutableStateFlow(0)

    val panels: StateFlow<List<PanelGalleryItem>> = combine(
        _workshopParentId,
        _sortOrder,
        _galleryRefresh,
    ) { parentId, sort, _ -> parentId to sort }
        .flatMapLatest { (parentId, sort) ->
            if (parentId == null) {
                flowOf(emptyList())
            } else {
                repo.getActiveSubfolders(parentId).map { subfolders ->
                    subfolders
                        .map { subfolder ->
                            PanelGalleryItem(
                                subfolderId = subfolder.id,
                                name = subfolder.name,
                                phase = WorkshopProjectPreferences.getProjectPhase(ctx, subfolder.id),
                                hasPublishedRelease = PanelReleaseStore.hasRelease(ctx, subfolder.id),
                                createdAt = subfolder.createdAt,
                                updatedAt = subfolder.updatedAt,
                            )
                        }
                        .sortedWith(galleryComparator(sort))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pinnedPanelIds: StateFlow<Set<Long>> =
        homePinRepo.observeUserPins()
            .map { pins -> pins.filter { it.pinType == HomePinType.PANEL }.map { it.targetId }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val userPins: StateFlow<List<PinnedRowItem.UserPin>> =
        homePinRepo.observeUserPins()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _workshopParentId.value = repo.getWorkshopParentId()
            backfillPublishedReleases()
        }
    }

    /** Re-read release flags and workshop phases (prefs are not in the Room subfolder flow). */
    fun refreshGallery() {
        _galleryRefresh.value += 1
    }

    private suspend fun backfillPublishedReleases() {
        val parentId = _workshopParentId.value ?: return
        withContext(Dispatchers.IO) {
            val db = appRef.database
            db.subfolderDao().getAllByParentOnce(parentId)
                .filter { it.deletedAt == null && !it.isSystemSubfolder }
                .forEach { subfolder ->
                    PanelReleaseStore.ensurePublishedIfComplete(ctx, db, subfolder.id)
                }
        }
        refreshGallery()
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun toggleGrid() {
        viewModelScope.launch {
            val next = layoutMode.value.next()
            ctx.settingsDataStore.edit { it[SettingsKeys.FOLDER_LAYOUT] = next.key }
        }
    }

    fun pinPanel(subfolderId: Long, name: String) {
        viewModelScope.launch {
            homePinRepo.pin(HomePinType.PANEL, subfolderId, name)
        }
    }

    fun unpinPanel(subfolderId: Long) {
        viewModelScope.launch {
            homePinRepo.unpin(HomePinType.PANEL, subfolderId)
        }
    }

    fun unpinById(pinId: Long) {
        viewModelScope.launch {
            homePinRepo.unpinById(pinId)
        }
    }
}

private fun galleryComparator(sort: SortOrder): Comparator<PanelGalleryItem> = when (sort) {
    SortOrder.NAME_ASC -> compareBy { it.name.lowercase() }
    SortOrder.NAME_DESC -> compareByDescending { it.name.lowercase() }
    SortOrder.CREATED_DESC -> compareByDescending { it.createdAt }
    SortOrder.UPDATED_DESC -> compareByDescending { it.updatedAt }
}
