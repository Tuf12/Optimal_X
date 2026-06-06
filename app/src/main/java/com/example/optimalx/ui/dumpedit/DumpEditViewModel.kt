package com.example.optimalx.ui.dumpedit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.preferences.DumpEditPreferences
import com.example.optimalx.data.preferences.DumpEditState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PromoteParentOption(
    val id: Long,
    val name: String,
)

sealed class PromoteResult {
    data class Success(
        val subfolderId: Long,
        val parentName: String,
        val subfolderName: String,
    ) : PromoteResult()

    data class Error(val message: String) : PromoteResult()
}

class DumpEditViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val appRef = app as OptimalXApplication

    val state: StateFlow<DumpEditState> = DumpEditPreferences.observeState(appContext)
        .onEach { loaded ->
            if (!historySeeded) {
                seedUndoHistory(loaded.content)
                historySeeded = true
                _contentReady.value = true
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DumpEditState())

    private val _contentReady = MutableStateFlow(false)
    val contentReady: StateFlow<Boolean> = _contentReady.asStateFlow()

    val isAiLocked: StateFlow<Boolean> = state
        .map { it.aiLocked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isAiBlind: StateFlow<Boolean> = state
        .map { it.aiBlind }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isViewMode = MutableStateFlow(false)
    val isViewMode: StateFlow<Boolean> = _isViewMode.asStateFlow()

    private val _restoreContent = MutableSharedFlow<String>()
    val restoreContent: SharedFlow<String> = _restoreContent.asSharedFlow()

    private val _showClearUndoSnackbar = MutableStateFlow(false)
    val showClearUndoSnackbar: StateFlow<Boolean> = _showClearUndoSnackbar.asStateFlow()

    private val _parentFolderOptions = MutableStateFlow<List<PromoteParentOption>>(emptyList())
    val parentFolderOptions: StateFlow<List<PromoteParentOption>> = _parentFolderOptions.asStateFlow()

    private val _promoteResult = MutableSharedFlow<PromoteResult>(extraBufferCapacity = 1)
    val promoteResult: SharedFlow<PromoteResult> = _promoteResult.asSharedFlow()

    /** Session-only snapshot for undo-clear; lost on process death. */
    private var undoClearSnapshot: String? = null

    val canUndoClear: Boolean
        get() = undoClearSnapshot != null

    private val contentHistory = ArrayDeque<String>()
    private var historyIndex = -1
    private var historySeeded = false

    fun onContentSave(html: String) {
        val current = contentHistory.getOrNull(historyIndex)
        if (html == current) return

        while (contentHistory.size > historyIndex + 1) contentHistory.removeLast()
        if (contentHistory.size >= 50) {
            contentHistory.removeFirst()
            historyIndex--
        }
        contentHistory.addLast(html)
        historyIndex = contentHistory.size - 1

        viewModelScope.launch(Dispatchers.IO) {
            DumpEditPreferences.saveContent(appContext, html)
        }
    }

    fun undo() {
        if (historyIndex <= 0) return
        historyIndex--
        val html = contentHistory[historyIndex]
        viewModelScope.launch { _restoreContent.emit(html) }
    }

    fun redo() {
        if (historyIndex >= contentHistory.size - 1) return
        historyIndex++
        val html = contentHistory[historyIndex]
        viewModelScope.launch { _restoreContent.emit(html) }
    }

    fun toggleViewMode() {
        _isViewMode.value = !_isViewMode.value
    }

    fun toggleAiLock() {
        viewModelScope.launch(Dispatchers.IO) {
            val next = !DumpEditPreferences.readState(appContext).aiLocked
            DumpEditPreferences.setAiLocked(appContext, next)
        }
    }

    fun toggleAiBlind() {
        viewModelScope.launch(Dispatchers.IO) {
            val next = !DumpEditPreferences.readState(appContext).aiBlind
            DumpEditPreferences.setAiBlind(appContext, next)
        }
    }

    fun confirmClear() {
        viewModelScope.launch {
            val current = DumpEditPreferences.readState(appContext).content
            undoClearSnapshot = current
            withContext(Dispatchers.IO) {
                DumpEditPreferences.clearContent(appContext)
            }
            seedUndoHistory("")
            _restoreContent.emit("")
            _showClearUndoSnackbar.value = true
        }
    }

    fun undoClear() {
        val snapshot = undoClearSnapshot ?: return
        undoClearSnapshot = null
        _showClearUndoSnackbar.value = false
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                DumpEditPreferences.saveContent(appContext, snapshot)
            }
            seedUndoHistory(snapshot)
            _restoreContent.emit(snapshot)
        }
    }

    fun dismissClearUndoSnackbar() {
        _showClearUndoSnackbar.value = false
    }

    fun loadPromoteTargets() {
        viewModelScope.launch(Dispatchers.IO) {
            val parents = appRef.database.parentFolderDao().getActiveUserFolders().first()
            _parentFolderOptions.value = parents.map { PromoteParentOption(it.id, it.name) }
        }
    }

    fun promoteToFolder(parentFolderId: Long, subfolderName: String) {
        viewModelScope.launch {
            val content = DumpEditPreferences.readState(appContext).content
            if (content.isBlank()) {
                _promoteResult.emit(PromoteResult.Error("Buffer is empty — nothing to promote."))
                return@launch
            }
            val trimmedName = subfolderName.trim()
            if (trimmedName.isBlank()) {
                _promoteResult.emit(PromoteResult.Error("Enter a subfolder name."))
                return@launch
            }
            try {
                val subfolderId = withContext(Dispatchers.IO) {
                    val id = appRef.folderRepository.createSubfolder(parentFolderId, trimmedName)
                    appRef.database.noteDao().getBySubfolderOnce(id)?.let { note ->
                        appRef.database.noteDao().update(
                            note.copy(content = content, updatedAt = System.currentTimeMillis()),
                        )
                        appRef.semanticChunkBuilder.indexNote(appRef.semanticIndexer, id)
                    }
                    appRef.semanticSyncService.requestSync("promote_dump_edit:$id")
                    appRef.appIndexSyncService.requestSync("promote_dump_edit:$id")
                    id
                }
                val parentName = appRef.database.parentFolderDao().getById(parentFolderId)?.name ?: "Folder"
                _promoteResult.emit(
                    PromoteResult.Success(
                        subfolderId = subfolderId,
                        parentName = parentName,
                        subfolderName = trimmedName,
                    ),
                )
            } catch (t: Throwable) {
                _promoteResult.emit(PromoteResult.Error(t.message ?: "Promote failed."))
            }
        }
    }

    private fun seedUndoHistory(content: String) {
        contentHistory.clear()
        contentHistory.addLast(content)
        historyIndex = 0
    }
}
