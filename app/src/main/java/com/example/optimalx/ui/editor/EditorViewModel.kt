package com.example.optimalx.ui.editor

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.CustomPanelAssignment
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.ui.components.NoteContentCodec
import com.example.optimalx.data.eidos.NoteSummaryCodec
import com.example.optimalx.data.eidos.NoteSummarySections
import com.example.optimalx.data.model.ContentCheckpoint
import com.example.optimalx.data.repository.EditorRepository
import com.example.optimalx.data.repository.SaveNoteSummaryResult
import com.example.optimalx.data.sync.SyncCallResult
import com.example.optimalx.data.sync.SyncFileService
import com.example.optimalx.data.revision.CHECKPOINT_AUTHOR_USER
import com.example.optimalx.data.revision.CHECKPOINT_LABEL_COMMITTED_EDITS
import com.example.optimalx.data.revision.CheckpointRepository
import com.example.optimalx.data.revision.PENDING_ITEM_STATUS_ACCEPTED
import com.example.optimalx.data.revision.PENDING_ITEM_STATUS_PENDING
import com.example.optimalx.data.revision.SCOPE_SUBFOLDER
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
import com.example.optimalx.data.revision.SOURCE_TYPE_NOTE
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

private const val WORKING_COPY_AUTOSAVE_MS = 800L

/**
 * Hosts the note editor's per-subfolder state (note body, AI lock/blind flags,
 * file references) and bridges into the DIFF_REVIEW checkpoint pipeline via
 * [SOURCE_TYPE_NOTE] so the user can see note history and restore prior versions
 * through the shared [com.example.optimalx.ui.workshop.components.ContentHistorySheet].
 *
 * Pending Eidos note edits are queued in DIFF_REVIEW ([SCOPE_SUBFOLDER]) until the user
 * accepts them in [com.example.optimalx.ui.workshop.review.DiffReviewScreen].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditorViewModel(
    app: Application,
    val subfolderId: Long,
) : AndroidViewModel(app) {

    private val appRef = app as OptimalXApplication
    private val readAloudSession: ReadAloudSession = appRef.readAloudSession
    private val syncFileService = SyncFileService(app.applicationContext, appRef.database)

    val noteReadAloudBarVisible: StateFlow<Boolean> = readAloudSession.barVisible
    val noteReadAloudIsPlaying: StateFlow<Boolean> = readAloudSession.isPlaying
    private val repo = EditorRepository(
        db = AppDatabase.getInstance(app),
        semanticIndexer = appRef.semanticIndexer,
        semanticSync = appRef.semanticSyncService,
        semanticChunkBuilder = appRef.semanticChunkBuilder,
    )
    private val db = AppDatabase.getInstance(app)

    // ── DIFF_REVIEW pending changes ─────────────────────────────────────────
    private val openPendingSet =
        db.pendingChangeDao()
            .observeOpenSetForScope(SCOPE_SUBFOLDER, subfolderId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pendingChangeCount: StateFlow<Int> = openPendingSet
        .flatMapLatest { set ->
            if (set == null) {
                flowOf(0)
            } else {
                db.pendingChangeDao().observeItems(set.id).map { items ->
                    items.count { it.status == PENDING_ITEM_STATUS_PENDING }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        viewModelScope.launch {
            openPendingSet
                .flatMapLatest { set ->
                    if (set == null) {
                        flowOf(0)
                    } else {
                        db.pendingChangeDao().observeItems(set.id).map { items ->
                            items.count { it.status == PENDING_ITEM_STATUS_ACCEPTED }
                        }
                    }
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { reloadNoteAfterDiffAccept() }
        }
    }

    // ── DB state ──────────────────────────────────────────────────────────────

    val subfolder: StateFlow<Subfolder?> = repo.getSubfolder(subfolderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val subfolderName: StateFlow<String> = subfolder
        .map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val parentFolderId: StateFlow<Long> = subfolder
        .map { it?.parentFolderId ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val note: StateFlow<Note?> = repo.getNote(subfolderId)
        .onEach { note ->
            if (note == null) return@onEach
            if (!editorInitialized) {
                val canonical = NoteContentCodec.normalizeLegacyToMarkdown(note.content)
                if (canonical != note.content) {
                    viewModelScope.launch(Dispatchers.IO) {
                        repo.saveNoteContent(subfolderId, canonical)
                    }
                }
                editorInitialized = true
                markNoteEditorLoaded(canonical, note.updatedAt)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isAiLocked: StateFlow<Boolean> = note
        .map { it?.aiLocked ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isAiBlind: StateFlow<Boolean> = note
        .map { it?.aiBlind ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val noteSummarySections: StateFlow<NoteSummarySections> = note
        .map { n -> if (n == null) NoteSummarySections() else NoteSummaryCodec.parse(n.summary) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NoteSummarySections())

    val noteSummaryUpdatedAt: StateFlow<Long> = note
        .map { it?.summaryUpdatedAt ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _summaryFeedback = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val summaryFeedback: SharedFlow<String> = _summaryFeedback.asSharedFlow()

    val fileReferences: StateFlow<List<FileReference>> = repo.getFileReferences(subfolderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customPanelAssignments: StateFlow<List<CustomPanelAssignment>> =
        db.customPanelAssignmentDao().getByTargetSubfolder(subfolderId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getCustomPanelHtml(assignment: CustomPanelAssignment): String {
        val ctx = getApplication<Application>().applicationContext
        val fromRelease = com.example.optimalx.data.panel.PanelReleaseStore
            .buildCompositeHtml(ctx, assignment.workshopSubfolderId)
        if (fromRelease != null) return fromRelease

        val files = runCatching {
            kotlinx.coroutines.runBlocking {
                db.fileReferenceDao().getBySubfolderOnce(assignment.workshopSubfolderId)
            }
        }.getOrDefault(emptyList())
        return com.example.optimalx.ui.workshop.PanelHtmlComposer.buildCompositeHtmlFromFileReferences(files)
    }

    fun getCustomPanelPreviewHtmlPath(assignment: CustomPanelAssignment): String? {
        val files = runCatching {
            kotlinx.coroutines.runBlocking {
                db.fileReferenceDao().getBySubfolderOnce(assignment.workshopSubfolderId)
            }
        }.getOrDefault(emptyList())
        val index = files.firstOrNull { it.fileName.equals("index.html", ignoreCase = true) }
        val html = index ?: files.firstOrNull { it.fileType.equals("html", ignoreCase = true) }
        return html?.filePath
    }

    fun removeCustomPanel(assignmentId: Long) {
        viewModelScope.launch {
            db.customPanelAssignmentDao().deleteById(assignmentId)
        }
    }

    fun addCustomPanel(workshopSubfolderId: Long, panelTitle: String) {
        viewModelScope.launch {
            db.customPanelAssignmentDao().insert(
                CustomPanelAssignment(
                    workshopSubfolderId = workshopSubfolderId,
                    targetSubfolderId = subfolderId,
                    panelTitle = panelTitle,
                )
            )
        }
    }

    private val _workshopProjects = MutableStateFlow<List<Subfolder>>(emptyList())
    val workshopProjects: StateFlow<List<Subfolder>> = _workshopProjects.asStateFlow()

    fun loadWorkshopProjects() {
        viewModelScope.launch {
            val workshopParent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.PANEL_WORKSHOP)
            if (workshopParent != null) {
                val ctx = getApplication<Application>().applicationContext
                com.example.optimalx.data.panel.PanelReleaseStore
                    .ensureAllWorkshopReleasesFromSources(ctx, db)
                val subs = db.subfolderDao().getAllByParentOnce(workshopParent.id)
                _workshopProjects.value = subs.filter { subfolder ->
                    subfolder.deletedAt == null &&
                        !subfolder.isSystemSubfolder &&
                        com.example.optimalx.data.panel.PanelReleaseStore.hasRelease(ctx, subfolder.id)
                }
            }
        }
    }

    // ── UI state ──────────────────────────────────────────────────────────────

    private val _isViewMode = MutableStateFlow(true)
    val isViewMode: StateFlow<Boolean> = _isViewMode.asStateFlow()

    private val _openFiles = MutableStateFlow<List<FileReference>>(emptyList())
    val openFiles: StateFlow<List<FileReference>> = _openFiles.asStateFlow()

    private val _fileFetchBusy = MutableStateFlow(false)
    val fileFetchBusy: StateFlow<Boolean> = _fileFetchBusy.asStateFlow()

    private val _fileFetchError = MutableStateFlow<String?>(null)
    val fileFetchError: StateFlow<String?> = _fileFetchError.asStateFlow()

    private val _fileOpenReady = MutableSharedFlow<FileReference>(extraBufferCapacity = 1)
    val fileOpenReady: SharedFlow<FileReference> = _fileOpenReady.asSharedFlow()
    private val fileViewPrefs = app.getSharedPreferences("file_view_state", Context.MODE_PRIVATE)

    private var editorInitialized = false

    private val _restoreContent = MutableSharedFlow<String>(replay = 1)
    val restoreContent: SharedFlow<String> = _restoreContent.asSharedFlow()

    // ── Eidos / external write sync ───────────────────────────────────────────
    // Mirrors workshop dirty + reload-after-send so open note buffers do not clobber tool writes.

    private val _isNoteDirty = MutableStateFlow(false)
    val isNoteDirty: StateFlow<Boolean> = _isNoteDirty.asStateFlow()

    private val _userHasEdited = MutableStateFlow(false)
    val userHasEdited: StateFlow<Boolean> = _userHasEdited.asStateFlow()

    private var pendingEditorContent: String? = null
    private var lastPersistedContent: String? = null
    private var lastLoadedUpdatedAt: Long = 0L
    private var editSessionBaseline: String? = null
    private var liveNoteContentProvider: (() -> String)? = null
    private var workingCopyAutosaveJob: Job? = null
    private val _editorHeadContent = MutableStateFlow("")

    private val _noteSaveStatus = MutableStateFlow(NoteEditorSaveStatus.Saved)
    val noteSaveStatus: StateFlow<NoteEditorSaveStatus> = _noteSaveStatus.asStateFlow()

    private val workingCopyFlusher = NoteEditorWorkingCopyFlusher(
        onSaving = { _noteSaveStatus.value = NoteEditorSaveStatus.Saving },
        onSaved = { _noteSaveStatus.value = NoteEditorSaveStatus.Saved },
        onPersist = { content -> persistWorkingCopy(content) },
    )

    /** Supplies the latest WYSIWYG markdown for flush-on-exit (avoids stale [pendingEditorContent]). */
    fun setLiveNoteContentProvider(provider: (() -> String)?) {
        liveNoteContentProvider = provider
    }

    /** Called when the user enters Edit mode and the WYSIWYG buffer is loaded. */
    fun onNoteEditSessionStarted(baseline: String) {
        editSessionBaseline = baseline
        _userHasEdited.value = false
        pendingEditorContent = baseline
        _editorHeadContent.value = baseline
    }

    /** Called from [NotePanel] when the rich-text buffer changes during an edit session. */
    fun onNoteEditorSnapshot(content: String) {
        pendingEditorContent = content
        val baseline = editSessionBaseline
        if (baseline != null && content != baseline) {
            _userHasEdited.value = true
        } else if (NoteEditorSyncPolicy.shouldPersistWorkingCopy(content, lastPersistedContent)) {
            // Edit session may not have started yet (view→edit race); still track real edits.
            _userHasEdited.value = true
        }
        val dirtyVsWorkingCopy = content != lastPersistedContent
        _isNoteDirty.value = dirtyVsWorkingCopy
        _noteSaveStatus.value = if (dirtyVsWorkingCopy) {
            NoteEditorSaveStatus.Unsaved
        } else {
            NoteEditorSaveStatus.Saved
        }
        _editorHeadContent.value = content
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

    /** Called after load, restore, or external reload — canonical row matches UI display. */
    fun markNoteEditorLoaded(content: String, updatedAt: Long = note.value?.updatedAt ?: 0L) {
        pendingEditorContent = content
        lastPersistedContent = content
        lastLoadedUpdatedAt = updatedAt
        _isNoteDirty.value = false
        _noteSaveStatus.value = NoteEditorSaveStatus.Saved
        _editorHeadContent.value = content
    }

    private fun markNoteEditorPersisted(content: String, updatedAt: Long) {
        pendingEditorContent = content
        lastPersistedContent = content
        lastLoadedUpdatedAt = updatedAt
        _isNoteDirty.value = false
        _noteSaveStatus.value = NoteEditorSaveStatus.Saved
        _editorHeadContent.value = content
        // Keep userHasEdited true while the user remains in Edit mode — clearing it
        // re-triggers NotePanel DB reload and wipes WYSIWYG spans / cursor.
    }

    /**
     * Reload note body from Room after Eidos (or another external writer) updates it.
     * Skips when the user has local unsaved edits ([isNoteDirty]).
     */
    fun reloadNoteAfterExternalWrite() {
        viewModelScope.launch {
            if (_userHasEdited.value) return@launch
            val latest = withContext(Dispatchers.IO) {
                db.noteDao().getBySubfolderOnce(subfolderId)
            } ?: return@launch
            if (!NoteEditorSyncPolicy.shouldReloadExternalWrite(
                    userHasEdited = _userHasEdited.value,
                    dbUpdatedAt = latest.updatedAt,
                    lastLoadedUpdatedAt = lastLoadedUpdatedAt,
                    dbContent = latest.content,
                    lastPersistedContent = lastPersistedContent,
                )
            ) {
                if (latest.updatedAt > lastLoadedUpdatedAt) {
                    lastLoadedUpdatedAt = latest.updatedAt
                }
                return@launch
            }
            _restoreContent.emit(latest.content)
            markNoteEditorLoaded(latest.content, latest.updatedAt)
        }
    }

    /**
     * After the user accepts a note Diff Review item, always reload from Room.
     * Clears a dirty editor buffer so accepted proposals are visible and are not
     * overwritten on NotePanel dispose / ON_STOP (mirrors [com.example.optimalx.ui.workshop.WorkshopEditorViewModel.reloadFromDiskAfterDiffAccept]).
     */
    fun reloadNoteAfterDiffAccept() {
        viewModelScope.launch {
            _isNoteDirty.value = false
            _userHasEdited.value = false
            editSessionBaseline = null
            val latest = withContext(Dispatchers.IO) {
                db.noteDao().getBySubfolderOnce(subfolderId)
            } ?: return@launch
            _restoreContent.emit(latest.content)
            markNoteEditorLoaded(latest.content, latest.updatedAt)
        }
    }

    /** Persist the open note buffer before an Eidos send so tools see what the user typed. */
    suspend fun flushNoteToDbForEidos() {
        val content = liveNoteContentProvider?.invoke() ?: pendingEditorContent ?: return
        if (!NoteEditorSyncPolicy.shouldPersistWorkingCopy(content, lastPersistedContent)) return
        persistWorkingCopy(content)
    }

    // ── DIFF_REVIEW checkpoint history ────────────────────────────────────────
    // Notes share `content_checkpoints` (`sourceType = "note"`, `sourceId = subfolderId`).
    // Working-copy flush ([persistWorkingCopy]) does not advance HEAD — only
    // [commitWorkingCopy], Diff Review accept, empty-note auto-apply, and restore do.
    private val checkpointRepository by lazy {
        CheckpointRepository(
            checkpointDao = db.contentCheckpointDao(),
            patchDao = db.contentPatchDao(),
            pendingChangeDao = db.pendingChangeDao(),
        )
    }

    /** Newest-first checkpoint timeline for this note. */
    val noteCheckpoints: StateFlow<List<ContentCheckpoint>> =
        db.contentCheckpointDao()
            .observeForSource(SOURCE_TYPE_NOTE, subfolderId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** True when editor content differs from the latest checkpoint (HEAD). */
    val isDirtyVsHead: StateFlow<Boolean> = combine(
        noteCheckpoints,
        _editorHeadContent,
    ) { checkpoints, content ->
        CheckpointRepository.isWorkingCopyDirtyVsHead(
            workingCopy = content,
            latestCheckpointHash = checkpoints.firstOrNull()?.contentHash,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _restoreFeedback = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val restoreFeedback: SharedFlow<String> = _restoreFeedback.asSharedFlow()

    private val _commitFeedback = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val commitFeedback: SharedFlow<String> = _commitFeedback.asSharedFlow()

    /**
     * Append a checkpoint (HEAD) for [content]. Skips when the latest checkpoint
     * already has the same hash. Does not write Room — caller must persist working copy first
     * when needed.
     */
    private suspend fun commitWorkingCopy(
        content: String,
        author: String = CHECKPOINT_AUTHOR_USER,
        label: String? = null,
    ): ContentCheckpoint? =
        checkpointRepository.commitWorkingCopyIfDirty(
            sourceType = SOURCE_TYPE_NOTE,
            sourceId = subfolderId,
            workingCopy = content,
            author = author,
            label = label,
        )

    /**
     * Commit the working copy to History (HEAD). Flushes the open buffer first when needed.
     * Does not reload the WYSIWYG buffer.
     */
    fun commitNoteToHead() {
        viewModelScope.launch {
            val buffer = liveNoteContentProvider?.invoke() ?: pendingEditorContent
            if (buffer != null &&
                NoteEditorSyncPolicy.shouldPersistWorkingCopy(buffer, lastPersistedContent)
            ) {
                persistWorkingCopy(buffer)
            }
            val working = withContext(Dispatchers.IO) {
                db.noteDao().getBySubfolderOnce(subfolderId)?.content.orEmpty()
            }
            val committed = withContext(Dispatchers.IO) {
                commitWorkingCopy(
                    content = working,
                    author = CHECKPOINT_AUTHOR_USER,
                    label = CHECKPOINT_LABEL_COMMITTED_EDITS,
                )
            }
            _editorHeadContent.value = working
            if (committed != null) {
                _commitFeedback.tryEmit("Committed to history")
            }
        }
    }

    /**
     * Restore the note body to [checkpointId]. Persists to Room before updating the
     * editor so a fast exit cannot leave the old working copy on disk.
     */
    fun restoreNoteCheckpoint(checkpointId: Long) {
        viewModelScope.launch {
            val cp = db.contentCheckpointDao().getById(checkpointId)
            if (cp == null) {
                _restoreFeedback.tryEmit("Checkpoint not found")
                return@launch
            }
            if (cp.sourceType != SOURCE_TYPE_NOTE || cp.sourceId != subfolderId) {
                _restoreFeedback.tryEmit("Checkpoint does not belong to this note")
                return@launch
            }
            val content = cp.contentBlob
            val updatedAt = withContext(Dispatchers.IO) {
                repo.saveNoteContent(subfolderId, content)
                val labelSuffix = cp.label?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                commitWorkingCopy(
                    content = content,
                    author = CHECKPOINT_AUTHOR_USER,
                    label = "Restored to seq ${cp.sequence}$labelSuffix",
                )
                db.noteDao().getBySubfolderOnce(subfolderId)?.updatedAt ?: System.currentTimeMillis()
            }
            markNoteEditorLoaded(content, updatedAt)
            _userHasEdited.value = false
            _isNoteDirty.value = false
            editSessionBaseline = content
            _editorHeadContent.value = content
            _restoreContent.emit(content)
            _restoreFeedback.tryEmit("Restored to seq ${cp.sequence}")
        }
    }

    // Called from NotePanel on teardown (dispose / ON_STOP) when dirty, or forced (dictation).
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

    /** Persist latest markdown to the working copy before export/share. */
    suspend fun flushNoteBeforeExport(content: String) {
        workingCopyFlusher.flush(
            content = content,
            shouldPersist = NoteEditorSyncPolicy.shouldPersistWorkingCopy(content, lastPersistedContent),
        )
    }

    /** Persist pending edits to the working copy before navigating away from the editor. */
    suspend fun flushNoteBeforeExit(content: String? = null) {
        val toSave = content ?: liveNoteContentProvider?.invoke() ?: pendingEditorContent ?: return
        workingCopyFlusher.flush(
            content = toSave,
            shouldPersist = NoteEditorSyncPolicy.shouldPersistWorkingCopy(toSave, lastPersistedContent),
        )
    }

    /** Write [content] to `notes.content` only — does not create a History checkpoint. */
    private suspend fun persistWorkingCopy(content: String) {
        withContext(Dispatchers.IO) {
            persistNoteContent(content)
        }
        val updatedAt = withContext(Dispatchers.IO) {
            db.noteDao().getBySubfolderOnce(subfolderId)?.updatedAt ?: System.currentTimeMillis()
        }
        markNoteEditorPersisted(content, updatedAt)
    }

    private suspend fun persistNoteContent(content: String) {
        val row = db.noteDao().getBySubfolderOnce(subfolderId)
        if (row != null &&
            NoteEditorSyncPolicy.shouldSkipPersistClobberingExternalWrite(
                dbUpdatedAt = row.updatedAt,
                lastLoadedUpdatedAt = lastLoadedUpdatedAt,
                persistContent = content,
                dbContent = row.content,
            )
        ) {
            return
        }

        if (content == lastPersistedContent) return

        repo.saveNoteContent(subfolderId, content)
    }

    // ── View mode / AI lock ───────────────────────────────────────────────────

    fun toggleViewMode() { _isViewMode.value = !_isViewMode.value }

    fun toggleAiLock() {
        viewModelScope.launch(Dispatchers.IO) { repo.toggleAiLock(subfolderId) }
    }

    fun toggleAiBlind() {
        viewModelScope.launch(Dispatchers.IO) { repo.toggleAiBlind(subfolderId) }
    }

    fun saveNoteSummary(memoryBullets: List<String>, contentDigest: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val sections = NoteSummarySections(
                memoryBullets = memoryBullets,
                contentDigest = contentDigest,
            )
            when (val result = repo.saveNoteSummarySections(subfolderId, sections)) {
                SaveNoteSummaryResult.Success ->
                    _summaryFeedback.emit("Folder summary saved.")
                is SaveNoteSummaryResult.Failed ->
                    _summaryFeedback.emit(result.message)
            }
        }
    }

    /** Starts chunked read-aloud for note [content] (HTML or markdown source) and shows the playback bar. */
    fun speakNoteAloud(content: String) = readAloudSession.startFromNote(content)

    fun toggleNoteReadAloudPlayback() = readAloudSession.togglePlayback()

    fun noteReadAloudRewind10Seconds() = readAloudSession.rewind10Seconds()

    fun noteReadAloudForward10Seconds() = readAloudSession.forward10Seconds()

    fun stopNoteSpeech() = readAloudSession.stop()

    // ── File panels ───────────────────────────────────────────────────────────

    fun openFile(ref: FileReference) {
        if (_openFiles.value.none { it.id == ref.id }) {
            _openFiles.value = _openFiles.value + ref
        }
    }

    fun requestOpenFile(ref: FileReference) {
        viewModelScope.launch {
            _fileFetchError.value = null
            _fileFetchBusy.value = true
            when (val result = syncFileService.ensureAttachmentLocal(ref)) {
                is SyncCallResult.Failure -> _fileFetchError.value = result.message
                is SyncCallResult.Success -> {
                    openFile(result.value)
                    _fileOpenReady.emit(result.value)
                }
            }
            _fileFetchBusy.value = false
        }
    }

    fun clearFileFetchError() {
        _fileFetchError.value = null
    }

    fun closeFile(ref: FileReference) {
        _openFiles.value = _openFiles.value.filter { it.id != ref.id }
    }

    fun getPdfRotation(fileId: Long): Float {
        return fileViewPrefs.getFloat("pdf_rotation_$fileId", 0f)
    }

    fun savePdfRotation(fileId: Long, rotation: Float) {
        fileViewPrefs.edit()
            .putFloat("pdf_rotation_$fileId", rotation)
            .apply()
    }

    fun getImageRotation(fileId: Long): Float {
        return fileViewPrefs.getFloat("img_rotation_$fileId", 0f)
    }

    fun saveImageRotation(fileId: Long, rotation: Float) {
        fileViewPrefs.edit()
            .putFloat("img_rotation_$fileId", rotation)
            .apply()
    }

    // ── File import ───────────────────────────────────────────────────────────

    fun importFile(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = resolveFileName(context, uri)
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val destDir = File(context.filesDir, "optimalx_files/$subfolderId").apply { mkdirs() }
            val destFile = File(destDir, "${System.currentTimeMillis()}_$fileName")

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (!destFile.isFile || destFile.length() <= 0L) return@launch
                repo.insertFileReference(
                    FileReference(
                        subfolderId = subfolderId,
                        fileName = fileName,
                        fileType = extension,
                        filePath = destFile.absolutePath,
                    )
                )
            } catch (_: Exception) { }
        }
    }

    fun deleteFileReference(ref: FileReference) {
        viewModelScope.launch(Dispatchers.IO) {
            closeFile(ref)
            repo.deleteFileReference(ref.id)
        }
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        var name = ""
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
        }
        return name.ifEmpty { uri.lastPathSegment?.substringAfterLast('/') ?: "file" }
    }
}