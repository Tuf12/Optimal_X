package com.example.optimalx.ui.folders

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.preferences.SettingsDefaults
import com.example.optimalx.data.preferences.SettingsKeys
import com.example.optimalx.data.preferences.settingsDataStore
import com.example.optimalx.data.repository.FolderRepository
import com.example.optimalx.data.repository.HomePinRepository
import com.example.optimalx.data.repository.SearchResult
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortOrder(val label: String) {
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    CREATED_DESC("Newest first"),
    UPDATED_DESC("Recently updated"),
}

data class FolderDisplayItem(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isSystem: Boolean = false,
)

enum class FolderLayoutMode(
    val key: String,
    val columns: Int,
    val label: String,
) {
    LIST("list", 1, "List"),
    GRID_2("grid2", 2, "2-col"),
    GRID_3("grid3", 3, "3-col"),
    GRID_4("grid4", 4, "4-col");

    fun next(): FolderLayoutMode = when (this) {
        LIST -> GRID_2
        GRID_2 -> GRID_3
        GRID_3 -> GRID_4
        GRID_4 -> LIST
    }

    companion object {
        fun fromKey(value: String): FolderLayoutMode =
            entries.firstOrNull { it.key == value } ?: GRID_2
    }
}

private fun ParentFolder.toDisplay() = FolderDisplayItem(id, name, createdAt, updatedAt, isSystem = isSystemFolder)

@OptIn(FlowPreview::class)
class ParentFolderViewModel(app: Application) : AndroidViewModel(app) {

    private val appRef = app as OptimalXApplication
    private val repo: FolderRepository = appRef.folderRepository
    private val homePinRepo: HomePinRepository = appRef.homePinRepository
    private val ctx = app.applicationContext

    private val _sortOrder = MutableStateFlow(SortOrder.NAME_ASC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    val layoutMode: StateFlow<FolderLayoutMode> = ctx.settingsDataStore.data
        .map { prefs -> FolderLayoutMode.fromKey(prefs[SettingsKeys.FOLDER_LAYOUT] ?: SettingsDefaults.FOLDER_LAYOUT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderLayoutMode.GRID_2)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    val folders: StateFlow<List<FolderDisplayItem>> = combine(
        repo.getActiveParentFolders(),
        _sortOrder,
    ) { list, sort ->
        list.map { it.toDisplay() }.sortedWith(sort.comparator())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _quickNotesParentId = MutableStateFlow<Long?>(null)
    val quickNotesParentId: StateFlow<Long?> = _quickNotesParentId.asStateFlow()

    private val _workshopParentId = MutableStateFlow<Long?>(null)
    val workshopParentId: StateFlow<Long?> = _workshopParentId.asStateFlow()

    /** User pin shortcuts on the parent-page pinned row. */
    val userPins: StateFlow<List<PinnedRowItem.UserPin>> =
        homePinRepo.observeUserPins()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pinnedParentIds: StateFlow<Set<Long>> = userPins
        .map { pins -> pins.filter { it.pinType == HomePinType.PARENT }.map { it.targetId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        viewModelScope.launch {
            _quickNotesParentId.value = repo.getQuickNotesParentId()
            _workshopParentId.value = repo.getWorkshopParentId()
        }
        viewModelScope.launch {
            _searchQuery.debounce(300).collect { query ->
                _searchResults.value = if (query.isBlank()) emptyList()
                else repo.searchAll(query)
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
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

    fun createFolder(name: String) {
        viewModelScope.launch { repo.createParentFolder(name.trim()) }
    }

    fun renameFolder(id: Long, newName: String) {
        viewModelScope.launch { repo.renameParentFolder(id, newName.trim()) }
    }

    fun deleteFolder(id: Long) {
        viewModelScope.launch { repo.softDeleteParentFolder(id) }
    }

    fun pinFolder(id: Long, name: String) {
        viewModelScope.launch { homePinRepo.pin(HomePinType.PARENT, id, name) }
    }

    fun unpinFolder(id: Long) {
        viewModelScope.launch { homePinRepo.unpin(HomePinType.PARENT, id) }
    }

    fun unpinById(pinId: Long) {
        viewModelScope.launch { homePinRepo.unpinById(pinId) }
    }
}

fun SortOrder.comparator(): Comparator<FolderDisplayItem> = when (this) {
    SortOrder.NAME_ASC -> compareBy { it.name.lowercase() }
    SortOrder.NAME_DESC -> compareByDescending { it.name.lowercase() }
    SortOrder.CREATED_DESC -> compareByDescending { it.createdAt }
    SortOrder.UPDATED_DESC -> compareByDescending { it.updatedAt }
}
