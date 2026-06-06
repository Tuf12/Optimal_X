package com.example.optimalx.ui.quicknotes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.quicknotes.QuickNoteEntry
import com.example.optimalx.data.quicknotes.QuickNotesFormat
import com.example.optimalx.data.repository.EditorRepository
import com.example.optimalx.data.repository.FolderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QuickNotesViewModel(
    app: Application,
    val subfolderId: Long,
) : AndroidViewModel(app) {

    private val appRef = app as OptimalXApplication
    private val folderRepo: FolderRepository = appRef.folderRepository
    private val editorRepo = EditorRepository(
        db = appRef.database,
        semanticIndexer = appRef.semanticIndexer,
        appIndexSync = appRef.appIndexSyncService,
        semanticSync = appRef.semanticSyncService,
        semanticChunkBuilder = appRef.semanticChunkBuilder,
    )

    val subfolder: StateFlow<Subfolder?> = editorRepo.getSubfolder(subfolderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val noteFlow = editorRepo.getNote(subfolderId)

    val entries: StateFlow<List<QuickNoteEntry>> = noteFlow
        .map { n -> QuickNotesFormat.parseEntries(n?.content.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _editingIndex = MutableStateFlow<Int?>(null)
    val editingIndex: StateFlow<Int?> = _editingIndex.asStateFlow()

    fun setDraft(value: String) {
        _draft.value = value
    }

    fun beginEdit(index: Int) {
        val list = entries.value
        if (index !in list.indices) return
        _editingIndex.value = index
        _draft.value = list[index].body
    }

    fun cancelEdit() {
        _editingIndex.value = null
        _draft.value = ""
    }

    fun sendDraft() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val note = appRef.database.noteDao().getBySubfolderOnce(subfolderId) ?: return@launch
            val editIdx = _editingIndex.value
            if (editIdx != null) {
                val parsed = QuickNotesFormat.parseEntries(note.content).toMutableList()
                if (editIdx !in parsed.indices) {
                    cancelEdit()
                    return@launch
                }
                val old = parsed[editIdx]
                parsed[editIdx] = old.copy(body = text)
                folderRepo.saveQuickNoteFullContent(
                    subfolderId,
                    QuickNotesFormat.serializeEntries(parsed),
                )
                _editingIndex.value = null
                _draft.value = ""
            } else {
                folderRepo.appendQuickNoteLine(subfolderId, text, System.currentTimeMillis())
                _draft.value = ""
            }
        }
    }

    fun deleteEntry(index: Int) {
        viewModelScope.launch {
            val note = appRef.database.noteDao().getBySubfolderOnce(subfolderId) ?: return@launch
            val parsed = QuickNotesFormat.parseEntries(note.content).toMutableList()
            if (index !in parsed.indices) return@launch
            parsed.removeAt(index)
            folderRepo.saveQuickNoteFullContent(
                subfolderId,
                QuickNotesFormat.serializeEntries(parsed),
            )
        }
    }
}
