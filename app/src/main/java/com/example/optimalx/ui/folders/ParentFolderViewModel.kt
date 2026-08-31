package com.example.optimalx.ui.folders

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.preferences.FolderListDisplayPreferences
import com.example.optimalx.data.preferences.FolderListScope
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
import kotlinx.coroutines.launch

enum class SortOrder(val label: String, val key: String) {
    NAME_ASC("Name A–Z", "name_asc"),
    NAME_DESC("Name Z–A", "name_desc"),
    CREATED_DESC("Newest first", "created_desc"),
    UPDATED_DESC("Recently updated", "updated_desc");

    companion object {
        fun fromKey(value: String?): SortOrder? =
            entries.firstOrNull { it.key == value }
    }
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

    private val listScope = FolderListScope.ParentHome

    val sortOrder: StateFlow<SortOrder> = FolderListDisplayPreferences
        .sortOrderFlow(ctx, listScope)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SortOrder.NAME_ASC)

    val layoutMode: StateFlow<FolderLayoutMode> = FolderListDisplayPreferences
        .layoutModeFlow(ctx, listScope)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderLayoutMode.GRID_2)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    val folders: StateFlow<List<FolderDisplayItem>> = combine(
        repo.getActiveParentFolders(),
        sortOrder,
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
        viewModelScope.launch {
            FolderListDisplayPreferences.setSortOrder(ctx, listScope, order)
        }
    }

    fun toggleGrid() {
        viewModelScope.launch {
            FolderListDisplayPreferences.setLayoutMode(ctx, listScope, layoutMode.value.next())
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
