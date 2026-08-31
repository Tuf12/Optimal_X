package com.example.optimalx.ui.dumpedit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.preferences.DumpEditPreferences
import com.example.optimalx.data.preferences.DumpEditState
import com.example.optimalx.ui.components.NoteContentCodec
import com.example.optimalx.ui.editor.NoteEditorWorkingCopyFlusher
import com.example.optimalx.ui.editor.NoteEditorSaveStatus
import com.example.optimalx.ui.editor.NoteEditorSyncPolicy
import com.example.optimalx.voice.ReadAloudSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

private const val WORKING_COPY_AUTOSAVE_MS = 800L

class DumpEditViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val appRef = app as OptimalXApplication
    private val readAloudSession: ReadAloudSession = appRef.readAloudSession

    val readAloudBarVisible: StateFlow<Boolean> = readAloudSession.barVisible
    val readAloudIsPlaying: StateFlow<Boolean> = readAloudSession.isPlaying

    val state: StateFlow<DumpEditState> = DumpEditPreferences.observeState(appContext)
        .onEach { loaded ->
            if (!contentInitialized) {
                val canonical = NoteContentCodec.normalizeLegacyToMarkdown(loaded.content)
                if (canonical != loaded.content) {
                    viewModelScope.launch(Dispatchers.IO) {
                        DumpEditPreferences.saveContent(appContext, canonical)
                    }
                }
                markEditorLoaded(canonical)
                contentInitialized = true
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

    private val _isViewMode = MutableStateFlow(true)
    val isViewMode: StateFlow<Boolean> = _isViewMode.asStateFlow()

    private val _userHasEdited = MutableStateFlow(false)
    val userHasEdited: StateFlow<Boolean> = _userHasEdited.asStateFlow()

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    private var editSessionBaseline: String? = null
    private var pendingEditorContent: String? = null
    private var lastPersistedContent: String? = null
    private var liveContentProvider: (() -> String)? = null
    private var workingCopyAutosaveJob: Job? = null

    private val _saveStatus = MutableStateFlow(NoteEditorSaveStatus.Saved)
    val saveStatus: StateFlow<NoteEditorSaveStatus> = _saveStatus.asStateFlow()

    private val workingCopyFlusher = NoteEditorWorkingCopyFlusher(
        onSaving = { _saveStatus.value = NoteEditorSaveStatus.Saving },
        onSaved = { _saveStatus.value = NoteEditorSaveStatus.Saved },
        onPersist = { content -> persistWorkingCopy(content) },
    )

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

    private var contentInitialized = false

    fun setLiveContentProvider(provider: (() -> String)?) {
        liveContentProvider = provider
    }

    fun onEditSessionStarted(baseline: String) {
        editSessionBaseline = baseline
        _userHasEdited.value = false
        pendingEditorContent = baseline
    }

    fun onEditorSnapshot(content: String) {
        pendingEditorContent = content
        val baseline = editSessionBaseline
        if (baseline != null && content != baseline) {
            _userHasEdited.value = true
        } else if (NoteEditorSyncPolicy.shouldPersistWorkingCopy(content, lastPersistedContent)) {
            _userHasEdited.value = true
        }
        val dirtyVsWorkingCopy = content != lastPersistedContent
        _isDirty.value = dirtyVsWorkingCopy
        _saveStatus.value = if (dirtyVsWorkingCopy) {
            NoteEditorSaveStatus.Unsaved
        } else {
            NoteEditorSaveStatus.Saved
        }
        scheduleWorkingCopyAutosave(content, dirtyVsWorkingCopy)
    }

    private fun scheduleWorkingCopyAutosave(content: String, dirty: Boolean) {
        workingCopyAutosaveJob?.cancel()
        if (!dirty) return
        workingCopyAutosaveJob = viewModelScope.launch {
            delay(WORKING_COPY_AUTOSAVE_MS)
            workingCopyFlusher.flush(
                content = content,
                shouldPersist = NoteEditorSyncPolicy.shouldPersistWorkingCopy(
                    content,
                    lastPersistedContent,
                ),
            )
        }
    }

    fun markEditorLoaded(content: String) {
        pendingEditorContent = content
        lastPersistedContent = content
        _isDirty.value = false
        _saveStatus.value = NoteEditorSaveStatus.Saved
    }

    fun onContentSave(content: String, force: Boolean = false) {
        viewModelScope.launch {
            workingCopyFlusher.flush(
                content = content,
                shouldPersist = force || NoteEditorSyncPolicy.shouldPersistWorkingCopy(
                    content,
                    lastPersistedContent,
                ),
            )
        }
    }

    suspend fun flushBeforeExport(content: String) {
        workingCopyFlusher.flush(
            content = content,
            shouldPersist = NoteEditorSyncPolicy.shouldPersistWorkingCopy(content, lastPersistedContent),
        )
    }

    suspend fun flushBeforeExit(content: String? = null) {
        val toSave = content ?: liveContentProvider?.invoke() ?: pendingEditorContent ?: return
        workingCopyFlusher.flush(
            content = toSave,
            shouldPersist = NoteEditorSyncPolicy.shouldPersistWorkingCopy(toSave, lastPersistedContent),
        )
    }

    private suspend fun persistWorkingCopy(content: String) {
        if (content == lastPersistedContent) return
        withContext(Dispatchers.IO) {
            DumpEditPreferences.saveContent(appContext, content)
        }
        lastPersistedContent = content
        pendingEditorContent = content
        _isDirty.value = false
        _saveStatus.value = NoteEditorSaveStatus.Saved
        // Keep userHasEdited during an active edit session (see EditorViewModel).
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
            markEditorLoaded("")
            _userHasEdited.value = false
            editSessionBaseline = null
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
            markEditorLoaded(snapshot)
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
            val content = NoteContentCodec.normalizeLegacyToMarkdown(
                DumpEditPreferences.readState(appContext).content,
            )
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

    fun speakAloud(content: String) = readAloudSession.startFromNote(content)

    fun toggleReadAloudPlayback() = readAloudSession.togglePlayback()

    fun readAloudRewind10Seconds() = readAloudSession.rewind10Seconds()

    fun readAloudForward10Seconds() = readAloudSession.forward10Seconds()

    fun stopSpeech() = readAloudSession.stop()
}
