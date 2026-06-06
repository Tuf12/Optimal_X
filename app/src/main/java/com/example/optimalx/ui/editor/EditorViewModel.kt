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
import com.example.optimalx.data.eidos.ContentSummaryChunksCodec
import com.example.optimalx.data.eidos.ContentSummaryResult
import com.example.optimalx.data.model.ContentCheckpoint
import com.example.optimalx.data.repository.EditorRepository
import com.example.optimalx.data.revision.CHECKPOINT_AUTHOR_USER
import com.example.optimalx.data.revision.CheckpointRepository
import com.example.optimalx.data.revision.ContentDiff
import com.example.optimalx.data.revision.SOURCE_TYPE_NOTE
import com.example.optimalx.voice.NOTE_READ_ALOUD_SKIP_CHARS
import com.example.optimalx.voice.Controls
import com.example.optimalx.voice.NoteReadAloudNotification
import com.example.optimalx.voice.NoteReadAloudSessionBridge
import com.example.optimalx.voice.TextToSpeechEngine
import com.example.optimalx.voice.buildReadAloudChunkStarts
import com.example.optimalx.voice.chunkIndexForCharOffset
import com.example.optimalx.voice.chunkNoteForReadAloud
import com.example.optimalx.voice.stripMarkdownForTts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hosts the note editor's per-subfolder state (note body, AI lock/blind flags,
 * file references, undo/redo ring) and bridges into the DIFF_REVIEW checkpoint
 * pipeline via [SOURCE_TYPE_NOTE] so the user can see note history and restore
 * prior versions through the shared [com.example.optimalx.ui.workshop.components.ContentHistorySheet].
 *
 * Pending-review for notes (i.e. queuing Eidos edits before they touch the
 * working copy) is still deferred — see Phase 7+ of
 * `app/docs/implementation/DIFF_REVIEW_IMPLEMENTATION_PLAN.md`.
 */
class EditorViewModel(
    app: Application,
    val subfolderId: Long,
) : AndroidViewModel(app), Controls {

    private val appRef = app as OptimalXApplication
    private var noteTts: TextToSpeechEngine? = null

    private val readAloudLock = Any()
    private var readAloudChunks: List<String> = emptyList()
    private var readAloudStarts: IntArray = IntArray(0)
    private var readAloudChunkIndex: Int = 0
    private var readAloudWantsPlaying: Boolean = false

    private val _noteReadAloudBarVisible = MutableStateFlow(false)
    val noteReadAloudBarVisible: StateFlow<Boolean> = _noteReadAloudBarVisible.asStateFlow()

    private val _noteReadAloudIsPlaying = MutableStateFlow(false)
    val noteReadAloudIsPlaying: StateFlow<Boolean> = _noteReadAloudIsPlaying.asStateFlow()
    private val appContext = app.applicationContext
    private val repo = EditorRepository(
        db = AppDatabase.getInstance(app),
        semanticIndexer = appRef.semanticIndexer,
        appIndexSync = appRef.appIndexSyncService,
        semanticSync = appRef.semanticSyncService,
        semanticChunkBuilder = appRef.semanticChunkBuilder,
    )

    init {
        NoteReadAloudSessionBridge.register(this)
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
            // Seed undo history on first load
            if (note != null && contentHistory.isEmpty()) {
                contentHistory.addLast(note.content)
                historyIndex = 0
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isAiLocked: StateFlow<Boolean> = note
        .map { it?.aiLocked ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isAiBlind: StateFlow<Boolean> = note
        .map { it?.aiBlind ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasNoteSummary: StateFlow<Boolean> = note
        .map { n ->
            n != null && (
                !n.summary.isNullOrBlank() ||
                    ContentSummaryChunksCodec.decode(n.summaryChunksJson).isNotEmpty()
                )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _summaryGenerating = MutableStateFlow(false)
    val summaryGenerating: StateFlow<Boolean> = _summaryGenerating.asStateFlow()

    private val _summaryFeedback = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val summaryFeedback: SharedFlow<String> = _summaryFeedback.asSharedFlow()

    val fileReferences: StateFlow<List<FileReference>> = repo.getFileReferences(subfolderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val db = AppDatabase.getInstance(app)

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
                val subs = db.subfolderDao().getAllByParentOnce(workshopParent.id)
                _workshopProjects.value = subs.filter { subfolder ->
                    subfolder.deletedAt == null &&
                        com.example.optimalx.data.panel.PanelReleaseStore.hasRelease(ctx, subfolder.id)
                }
            }
        }
    }

    // ── UI state ──────────────────────────────────────────────────────────────

    private val _isViewMode = MutableStateFlow(false)
    val isViewMode: StateFlow<Boolean> = _isViewMode.asStateFlow()

    private val _openFiles = MutableStateFlow<List<FileReference>>(emptyList())
    val openFiles: StateFlow<List<FileReference>> = _openFiles.asStateFlow()
    private val fileViewPrefs = app.getSharedPreferences("file_view_state", Context.MODE_PRIVATE)

    // ── Undo / Redo ───────────────────────────────────────────────────────────

    private val contentHistory = ArrayDeque<String>()
    private var historyIndex = -1

    private val _restoreContent = MutableSharedFlow<String>()
    val restoreContent: SharedFlow<String> = _restoreContent.asSharedFlow()

    val canUndo: StateFlow<Boolean> = MutableStateFlow(false) // updated on each history change
    val canRedo: StateFlow<Boolean> = MutableStateFlow(false)

    // ── DIFF_REVIEW checkpoint history ────────────────────────────────────────
    // Notes share the same `content_checkpoints` table as workshop files (with
    // `sourceType = "note"`, `sourceId = subfolderId`). Saves snapshot via
    // [snapshotNoteCheckpoint] so the user can browse history and restore via
    // the shared [ContentHistorySheet].
    private val checkpointRepository by lazy {
        CheckpointRepository(
            checkpointDao = db.contentCheckpointDao(),
            patchDao = db.contentPatchDao(),
        )
    }

    /** Newest-first checkpoint timeline for this note. */
    val noteCheckpoints: StateFlow<List<ContentCheckpoint>> =
        db.contentCheckpointDao()
            .observeForSource(SOURCE_TYPE_NOTE, subfolderId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _restoreFeedback = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val restoreFeedback: SharedFlow<String> = _restoreFeedback.asSharedFlow()

    /**
     * Append a checkpoint for [content] under `(note, subfolderId)`. Skips when the
     * latest checkpoint already has the same hash so back-to-back saves with no
     * net change don't churn the timeline.
     */
    private suspend fun snapshotNoteCheckpoint(
        content: String,
        author: String = CHECKPOINT_AUTHOR_USER,
        label: String? = null,
    ) {
        val latest = checkpointRepository.getLatest(SOURCE_TYPE_NOTE, subfolderId)
        val newHash = ContentDiff.sha256Hex(content)
        if (latest != null && latest.contentHash == newHash) return
        // Capture the first save as the baseline so the timeline is anchored.
        checkpointRepository.baselineIfMissing(
            sourceType = SOURCE_TYPE_NOTE,
            sourceId = subfolderId,
            content = latest?.contentBlob ?: content,
        )
        checkpointRepository.createCheckpoint(
            sourceType = SOURCE_TYPE_NOTE,
            sourceId = subfolderId,
            content = content,
            author = author,
            label = label,
        )
    }

    /**
     * Restore the note body to [checkpointId]. Writes via [EditorRepository.saveNoteContent]
     * so semantic + app-index sync run, emits to [restoreContent] so the open
     * editor view replaces its buffer, and appends a fresh `user`-authored
     * "Restored to seq N (...)" checkpoint so the timeline shows the restore.
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
            // Replace the open editor buffer so the WYSIWYG view refreshes.
            _restoreContent.emit(content)
            // Keep undo/redo in sync with the restored content.
            seedUndoHistoryAfterRestore(content)
            withContext(Dispatchers.IO) {
                repo.saveNoteContent(subfolderId, content)
                val labelSuffix = cp.label?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                snapshotNoteCheckpoint(
                    content = content,
                    author = CHECKPOINT_AUTHOR_USER,
                    label = "Restored to seq ${cp.sequence}$labelSuffix",
                )
            }
            _restoreFeedback.tryEmit("Restored to seq ${cp.sequence}")
        }
    }

    private fun seedUndoHistoryAfterRestore(content: String) {
        val current = contentHistory.getOrNull(historyIndex)
        if (content == current) return
        while (contentHistory.size > historyIndex + 1) contentHistory.removeLast()
        if (contentHistory.size >= 50) {
            contentHistory.removeFirst()
            historyIndex--
        }
        contentHistory.addLast(content)
        historyIndex = contentHistory.size - 1
    }

    // Called from NotePanel on teardown (dispose / ON_STOP) and undo/redo.
    fun onContentSave(html: String) {
        val current = contentHistory.getOrNull(historyIndex)
        if (html == current) return

        // Trim redo stack
        while (contentHistory.size > historyIndex + 1) contentHistory.removeLast()

        // Cap history at 50 entries
        if (contentHistory.size >= 50) {
            contentHistory.removeFirst()
            historyIndex--
        }

        contentHistory.addLast(html)
        historyIndex = contentHistory.size - 1

        viewModelScope.launch(Dispatchers.IO) {
            repo.saveNoteContent(subfolderId, html)
            snapshotNoteCheckpoint(content = html)
        }
    }

    fun undo() {
        if (historyIndex <= 0) return
        historyIndex--
        val content = contentHistory[historyIndex]
        viewModelScope.launch {
            _restoreContent.emit(content)
            withContext(Dispatchers.IO) {
                repo.saveNoteContent(subfolderId, content)
                snapshotNoteCheckpoint(content = content)
            }
        }
    }

    fun redo() {
        if (historyIndex >= contentHistory.size - 1) return
        historyIndex++
        val content = contentHistory[historyIndex]
        viewModelScope.launch {
            _restoreContent.emit(content)
            withContext(Dispatchers.IO) {
                repo.saveNoteContent(subfolderId, content)
                snapshotNoteCheckpoint(content = content)
            }
        }
    }

    // ── View mode / AI lock ───────────────────────────────────────────────────

    fun toggleViewMode() { _isViewMode.value = !_isViewMode.value }

    fun toggleAiLock() {
        viewModelScope.launch(Dispatchers.IO) { repo.toggleAiLock(subfolderId) }
    }

    fun toggleAiBlind() {
        viewModelScope.launch(Dispatchers.IO) { repo.toggleAiBlind(subfolderId) }
    }

    fun generateOrRegenerateNoteSummary() {
        viewModelScope.launch {
            val current = note.value ?: run {
                _summaryFeedback.emit("No note to summarize.")
                return@launch
            }
            if (current.aiBlind) {
                _summaryFeedback.emit("Note is blind from Eidos. Reveal it first.")
                return@launch
            }
            val hadSummary = hasNoteSummary.value
            _summaryGenerating.value = true
            val result = appRef.contentSummaryService.generateNoteSummary(current.id)
            _summaryGenerating.value = false
            val message = when (result) {
                ContentSummaryResult.Success ->
                    if (hadSummary) "Eidos summary updated." else "Eidos summary generated."
                is ContentSummaryResult.Failed -> result.message
            }
            _summaryFeedback.emit(message)
        }
    }

    /** Starts chunked read-aloud for [plainText] and shows the in-note playback bar. */
    fun speakNoteAloud(plainText: String) {
        val sanitized = stripMarkdownForTts(plainText.trim())
        if (sanitized.isBlank()) return
        val chunks = chunkNoteForReadAloud(sanitized)
        if (chunks.isEmpty()) return

        synchronized(readAloudLock) {
            readAloudChunks = chunks
            readAloudStarts = buildReadAloudChunkStarts(chunks)
            readAloudChunkIndex = 0
            readAloudWantsPlaying = true
            _noteReadAloudBarVisible.value = true
            _noteReadAloudIsPlaying.value = true
        }
        syncReadAloudNotification()
        ensureNoteTts().stop()
        enqueueSpeakChainFromCurrentChunk()
    }

    fun toggleNoteReadAloudPlayback() {
        val startPlayback: Boolean
        synchronized(readAloudLock) {
            if (!_noteReadAloudBarVisible.value || readAloudChunks.isEmpty()) return
            startPlayback = !_noteReadAloudIsPlaying.value
            readAloudWantsPlaying = startPlayback
            _noteReadAloudIsPlaying.value = startPlayback
            if (!startPlayback) {
                noteTts?.stop()
                syncReadAloudNotification()
                return
            }
            if (readAloudChunkIndex >= readAloudChunks.size) {
                readAloudChunkIndex = 0
            }
        }
        syncReadAloudNotification()
        if (startPlayback) enqueueSpeakChainFromCurrentChunk()
    }

    fun noteReadAloudRewind10Seconds() {
        val shouldResume: Boolean
        synchronized(readAloudLock) {
            if (readAloudChunks.isEmpty()) return
            val idx = readAloudChunkIndex.coerceAtMost(readAloudChunks.lastIndex.coerceAtLeast(0))
            val curStart = readAloudStarts[idx]
            val targetOffset = (curStart - NOTE_READ_ALOUD_SKIP_CHARS).coerceAtLeast(0)
            readAloudChunkIndex = chunkIndexForCharOffset(readAloudStarts, targetOffset)
            shouldResume = readAloudWantsPlaying
        }
        noteTts?.stop()
        syncReadAloudNotification()
        if (shouldResume) enqueueSpeakChainFromCurrentChunk()
    }

    fun noteReadAloudForward10Seconds() {
        val shouldResume: Boolean
        synchronized(readAloudLock) {
            if (readAloudChunks.isEmpty()) return
            val totalLen = readAloudStarts.last()
            val idx = readAloudChunkIndex.coerceAtMost(readAloudChunks.lastIndex.coerceAtLeast(0))
            val curStart = readAloudStarts[idx]
            val targetOffset =
                (curStart + NOTE_READ_ALOUD_SKIP_CHARS).coerceAtMost((totalLen - 1).coerceAtLeast(0))
            readAloudChunkIndex = chunkIndexForCharOffset(readAloudStarts, targetOffset)
            shouldResume = readAloudWantsPlaying
        }
        noteTts?.stop()
        syncReadAloudNotification()
        if (shouldResume) enqueueSpeakChainFromCurrentChunk()
    }

    fun stopNoteSpeech() {
        synchronized(readAloudLock) {
            readAloudWantsPlaying = false
            readAloudChunks = emptyList()
            readAloudStarts = IntArray(0)
            readAloudChunkIndex = 0
            _noteReadAloudBarVisible.value = false
            _noteReadAloudIsPlaying.value = false
        }
        noteTts?.stop()
        syncReadAloudNotification()
    }

    private fun ensureNoteTts(): TextToSpeechEngine {
        return noteTts ?: TextToSpeechEngine(getApplication()).also { noteTts = it }
    }

    private fun enqueueSpeakChainFromCurrentChunk() {
        viewModelScope.launch(Dispatchers.Main) {
            val chunkText: String
            val utteranceIndex: Int
            synchronized(readAloudLock) {
                if (!readAloudWantsPlaying) return@launch
                if (readAloudChunkIndex >= readAloudChunks.size) {
                    readAloudWantsPlaying = false
                    _noteReadAloudIsPlaying.value = false
                    syncReadAloudNotification()
                    return@launch
                }
                utteranceIndex = readAloudChunkIndex
                chunkText = readAloudChunks[utteranceIndex]
            }
            ensureNoteTts().speak(chunkText) {
                viewModelScope.launch(Dispatchers.Main) {
                    synchronized(readAloudLock) {
                        if (!readAloudWantsPlaying) return@launch
                        if (readAloudChunkIndex != utteranceIndex) return@launch
                        readAloudChunkIndex++
                        if (readAloudChunkIndex >= readAloudChunks.size) {
                            readAloudWantsPlaying = false
                            _noteReadAloudIsPlaying.value = false
                            syncReadAloudNotification()
                            return@launch
                        }
                    }
                    enqueueSpeakChainFromCurrentChunk()
                }
            }
        }
    }

    private fun syncReadAloudNotification() {
        val visible = _noteReadAloudBarVisible.value
        if (!visible) {
            NoteReadAloudNotification.hide(appContext)
            return
        }
        NoteReadAloudNotification.show(appContext, _noteReadAloudIsPlaying.value)
    }

    override fun onToggle() = toggleNoteReadAloudPlayback()

    override fun onRewind10() = noteReadAloudRewind10Seconds()

    override fun onForward10() = noteReadAloudForward10Seconds()

    override fun onStop() = stopNoteSpeech()

    // ── File panels ───────────────────────────────────────────────────────────

    fun openFile(ref: FileReference) {
        if (_openFiles.value.none { it.id == ref.id }) {
            _openFiles.value = _openFiles.value + ref
        }
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
            File(ref.filePath).delete()
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

    override fun onCleared() {
        synchronized(readAloudLock) {
            readAloudChunks = emptyList()
            readAloudStarts = IntArray(0)
            readAloudWantsPlaying = false
            _noteReadAloudBarVisible.value = false
            _noteReadAloudIsPlaying.value = false
        }
        NoteReadAloudSessionBridge.unregister(this)
        NoteReadAloudNotification.hide(appContext)
        noteTts?.destroy()
        noteTts = null
        super.onCleared()
    }
}
