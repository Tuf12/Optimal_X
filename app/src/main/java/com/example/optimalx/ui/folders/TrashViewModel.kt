package com.example.optimalx.ui.folders

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.repository.FolderRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrashViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: FolderRepository = (app as OptimalXApplication).folderRepository

    val deletedParentFolders: StateFlow<List<ParentFolder>> =
        repo.getDeletedParentFolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deletedSubfolders: StateFlow<List<Subfolder>> =
        repo.getDeletedSubfolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun restoreParentFolder(id: Long) {
        viewModelScope.launch { repo.restoreParentFolder(id) }
    }

    fun restoreSubfolder(id: Long) {
        viewModelScope.launch { repo.restoreSubfolder(id) }
    }

    fun permanentlyDeleteParentFolder(id: Long) {
        viewModelScope.launch { repo.permanentlyDeleteParentFolder(id) }
    }

    fun permanentlyDeleteSubfolder(id: Long) {
        viewModelScope.launch {
            repo.permanentlyDeleteSubfolder(getApplication<Application>().applicationContext, id)
        }
    }
}
