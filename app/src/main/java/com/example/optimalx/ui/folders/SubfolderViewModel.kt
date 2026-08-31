package com.example.optimalx.ui.folders

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.quicknotes.QuickNotesFormat
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
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

private fun Subfolder.toDisplay() = FolderDisplayItem(id, name, createdAt, updatedAt, isSystem = isSystemSubfolder)

@OptIn(FlowPreview::class)
class SubfolderViewModel(
    app: Application,
    val parentFolderId: Long,
) : AndroidViewModel(app) {

    private val appRef = app as OptimalXApplication
    private val repo: FolderRepository = appRef.folderRepository
    private val homePinRepo: HomePinRepository = appRef.homePinRepository
    private val ctx = app.applicationContext

    private val listScope = FolderListScope.Subfolder(parentFolderId)

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

    private val _isQuickNotesParent = MutableStateFlow(false)
    val isQuickNotesParent: StateFlow<Boolean> = _isQuickNotesParent.asStateFlow()

    private val _isWorkshopParent = MutableStateFlow(false)
    val isWorkshopParent: StateFlow<Boolean> = _isWorkshopParent.asStateFlow()

    val subfolders: StateFlow<List<FolderDisplayItem>> = combine(
        repo.getActiveSubfolders(parentFolderId),
        sortOrder,
    ) { list, sort ->
        list.map { it.toDisplay() }.sortedWith(sort.comparator())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pinnedSubfolderIds: StateFlow<Set<Long>> =
        homePinRepo.observeUserPins()
            .map { pins -> pins.filter { it.pinType == HomePinType.SUBFOLDER }.map { it.targetId }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // All parent folders — needed for the Move dialog (system parents excluded)
    val allParentFolders: StateFlow<List<ParentFolder>> =
        repo.getAllActiveParentFolders()
            .map { list -> list.filter { !it.isSystemFolder } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val parent = repo.getParentFolderById(parentFolderId)
            val isQn = parent?.name == SystemFolderNames.QUICK_NOTES
            _isQuickNotesParent.value = isQn
            _isWorkshopParent.value = parent?.name == SystemFolderNames.PANEL_WORKSHOP
            if (isQn) {
                repo.ensureQuickNotesShowsTodayWhenEmpty(parentFolderId)
            }
        }
        viewModelScope.launch {
            _searchQuery.debounce(300).collect { query ->
                _searchResults.value = if (query.isBlank()) emptyList()
                else repo.searchAll(query)
            }
        }
    }

    suspend fun shouldOpenQuickNotesInbox(subfolderId: Long): Boolean {
        val parent = repo.getParentFolderById(parentFolderId) ?: return false
        if (parent.name != SystemFolderNames.QUICK_NOTES) return false
        val sf = repo.getSubfolderById(subfolderId) ?: return false
        return QuickNotesFormat.isIsoDateFolderName(sf.name)
    }

    fun onSearchQueryChange(query: String) { _searchQuery.value = query }
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

    fun createSubfolder(name: String) {
        viewModelScope.launch {
            if (_isQuickNotesParent.value) return@launch
            if (_isWorkshopParent.value) {
                repo.createWorkshopProject(ctx, parentFolderId, name.trim())
            } else {
                repo.createSubfolder(parentFolderId, name.trim())
            }
        }
    }

    fun renameSubfolder(id: Long, newName: String) {
        viewModelScope.launch { repo.renameSubfolder(id, newName.trim()) }
    }

    fun deleteSubfolder(id: Long) {
        viewModelScope.launch { repo.softDeleteSubfolder(id) }
    }

    fun moveSubfolder(subfolderId: Long, newParentId: Long) {
        viewModelScope.launch {
            if (repo.getParentFolderById(newParentId)?.name == SystemFolderNames.QUICK_NOTES) return@launch
            repo.moveSubfolder(subfolderId, newParentId)
        }
    }

    fun pinSubfolder(id: Long, name: String) {
        viewModelScope.launch { homePinRepo.pin(HomePinType.SUBFOLDER, id, name) }
    }

    fun unpinSubfolder(id: Long) {
        viewModelScope.launch { homePinRepo.unpin(HomePinType.SUBFOLDER, id) }
    }
}
