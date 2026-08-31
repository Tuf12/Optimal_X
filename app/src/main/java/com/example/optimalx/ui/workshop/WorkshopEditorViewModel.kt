package com.example.optimalx.ui.workshop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.eidos.ContentSummaryResult
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.eidos.WorkshopDocAlignGate
import com.example.optimalx.data.eidos.WorkshopDocAlignScope
import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.eidos.WorkshopProjectSummaryAutomation
import com.example.optimalx.data.sync.SyncCallResult
import com.example.optimalx.data.sync.SyncFilePathResolver
import com.example.optimalx.data.sync.WorkshopDiskCheck
import com.example.optimalx.data.sync.SyncFileService
import com.example.optimalx.data.sync.WorkshopBackupProgress
import com.example.optimalx.data.eidos.PanelPlatformSpec
import com.example.optimalx.data.eidos.WorkshopSpecValidation
import android.util.Log
import com.example.optimalx.data.panel.PanelReleaseStore
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import com.example.optimalx.data.repository.FolderRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.example.optimalx.ui.eidos.EidosChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Shown when Accept logic is blocked until persistence hooks exist or user overrides. */
data class PersistenceFinishGate(
    val warnings: List<String>,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkshopEditorViewModel(
    app: Application,
    val subfolderId: Long,
) : AndroidViewModel(app) {

    private val appRef = app as OptimalXApplication
    private val db: AppDatabase = appRef.database
    private val ctx = app.applicationContext
    private val syncFileService = SyncFileService(ctx, db)

    val files: StateFlow<List<FileReference>> = db.fileReferenceDao()
        .getBySubfolder(subfolderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── DIFF_REVIEW pending changes ─────────────────────────────────────────
    // The open pending set for this workshop project. The badge in the top bar
    // and the auto-reload-on-accept effect both derive from this flow.
    private val openPendingSet: StateFlow<com.example.optimalx.data.model.PendingChangeSet?> =
        db.pendingChangeDao()
            .observeOpenSetForScope(
                scopeType = com.example.optimalx.data.revision.SCOPE_WORKSHOP_PROJECT,
                scopeId = subfolderId,
            )
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Open pending change set id for this workshop project, or null if none. */
    val pendingChangeSetId: StateFlow<Long?> = openPendingSet
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Number of pending-status items in the open set for this workshop project. */
    val pendingChangeCount: StateFlow<Int> = openPendingSet
        .flatMapLatest { set ->
            if (set == null) {
                flowOf(0)
            } else {
                db.pendingChangeDao().observeItems(set.id).map { items ->
                    items.count { it.status == com.example.optimalx.data.revision.PENDING_ITEM_STATUS_PENDING }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        appRef.semanticSyncService.requestSync("workshop_editor_open:$subfolderId")

        // When an item is accepted, the disk file just changed under our feet —
        // refresh the editor buffer so it doesn't go stale. Triggered by the
        // accepted-count rising for the currently open set.
        viewModelScope.launch {
            openPendingSet
                .flatMapLatest { set ->
                    if (set == null) {
                        flowOf(0)
                    } else {
                        db.pendingChangeDao().observeItems(set.id).map { items ->
                            items.count { it.status == com.example.optimalx.data.revision.PENDING_ITEM_STATUS_ACCEPTED }
                        }
                    }
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { reloadFromDiskAfterDiffAccept() }
        }
    }

    // ── Checkpoint history (DIFF_REVIEW phase 6) ─────────────────────────────
    // Lazily-built checkpoint pipeline so Restore goes through the same
    // disk-write + reindex + new-checkpoint code path as Eidos writes.
    private val checkpointRepository by lazy {
        com.example.optimalx.data.revision.CheckpointRepository(
            checkpointDao = db.contentCheckpointDao(),
            patchDao = db.contentPatchDao(),
            pendingChangeDao = db.pendingChangeDao(),
        )
    }
    private val directWriteApplier by lazy {
        com.example.optimalx.data.revision.DirectWriteApplier(
            fileReferenceDao = db.fileReferenceDao(),
            checkpointRepository = checkpointRepository,
            indexer = com.example.optimalx.data.revision.WorkshopFileIndexer { ref, content ->
                appRef.semanticChunkBuilder.indexFile(appRef.semanticIndexer, ref, content)
            },
            noteDao = db.noteDao(),
            workshopFilePath = { subId, fileName ->
                val dir = File(appRef.filesDir, "workshop/$subId").also { it.mkdirs() }
                File(dir, fileName)
            },
        )
    }

    private val _currentFileId = MutableStateFlow<Long?>(null)
    val currentFileId: StateFlow<Long?> = _currentFileId.asStateFlow()

    /**
     * Checkpoint timeline for the currently open file (newest first).
     * Empty when no file is open or no checkpoints exist yet.
     */
    val checkpointsForCurrentFile: StateFlow<List<com.example.optimalx.data.model.ContentCheckpoint>> =
        _currentFileId
            .flatMapLatest { fileId ->
                if (fileId == null) {
                    flowOf(emptyList())
                } else {
                    db.contentCheckpointDao().observeForSource(
                        sourceType = com.example.optimalx.data.revision.SOURCE_TYPE_WORKSHOP_FILE,
                        sourceId = fileId,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _restoreFeedback = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val restoreFeedback: SharedFlow<String> = _restoreFeedback.asSharedFlow()

    private val _workshopBackupBusy = MutableStateFlow(false)
    val workshopBackupBusy: StateFlow<Boolean> = _workshopBackupBusy.asStateFlow()

    private val _workshopRestoreInProgress = MutableStateFlow(false)
    val workshopRestoreInProgress: StateFlow<Boolean> = _workshopRestoreInProgress.asStateFlow()

    private val _workshopBackupProgress = MutableStateFlow<WorkshopBackupProgress?>(null)
    val workshopBackupProgress: StateFlow<WorkshopBackupProgress?> = _workshopBackupProgress.asStateFlow()

    private val _workshopBackupMessage = MutableStateFlow<String?>(null)
    val workshopBackupMessage: StateFlow<String?> = _workshopBackupMessage.asStateFlow()

    private val _needsDesktopFileRestore = MutableStateFlow(false)
    val needsDesktopFileRestore: StateFlow<Boolean> = _needsDesktopFileRestore.asStateFlow()

    private val _workshopRestoreMessage = MutableStateFlow<String?>(null)
    val workshopRestoreMessage: StateFlow<String?> = _workshopRestoreMessage.asStateFlow()

    /**
     * Restore the currently open file's content to [checkpointId]. Writes via
     * [DirectWriteApplier] so a fresh `user`-authored checkpoint labeled
     * `"Restored to seq N (<original label>)"` is appended to the timeline.
     */
    fun restoreCheckpoint(checkpointId: Long) {
        val fileId = _currentFileId.value ?: return
        viewModelScope.launch {
            val cp = db.contentCheckpointDao().getById(checkpointId)
            if (cp == null) {
                _restoreFeedback.tryEmit("Checkpoint not found")
                return@launch
            }
            if (cp.sourceId != fileId ||
                cp.sourceType != com.example.optimalx.data.revision.SOURCE_TYPE_WORKSHOP_FILE) {
                _restoreFeedback.tryEmit("Checkpoint does not belong to this file")
                return@launch
            }
            val ref = db.fileReferenceDao().getById(fileId)
            if (ref == null) {
                _restoreFeedback.tryEmit("File no longer exists")
                return@launch
            }
            val labelSuffix = cp.label?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            val result = directWriteApplier.applyWorkshopWrite(
                ref = ref,
                content = cp.contentBlob,
                author = com.example.optimalx.data.revision.CHECKPOINT_AUTHOR_USER,
                label = "Restored to seq ${cp.sequence}$labelSuffix",
                conversationId = null,
            )
            when (result) {
                is com.example.optimalx.data.revision.WriteResult.Applied -> {
                    reloadOpenFileFromDisk()
                    _restoreFeedback.tryEmit("Restored to seq ${cp.sequence}")
                }
                is com.example.optimalx.data.revision.WriteResult.Failed -> {
                    _restoreFeedback.tryEmit("Restore failed: ${result.message}")
                }
            }
        }
    }

    private val _fileContent = MutableStateFlow("")
    val fileContent: StateFlow<String> = _fileContent.asStateFlow()

    /** True when the user edited the open file locally; cleared on open/reload from disk. */
    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    /** Bumped when workshop files change on disk outside the editor (e.g. Eidos tool writes). */
    private val _diskRevision = MutableStateFlow(0)
    val diskRevision: StateFlow<Int> = _diskRevision.asStateFlow()

    private val _isPreviewMode = MutableStateFlow(false)
    val isPreviewMode: StateFlow<Boolean> = _isPreviewMode.asStateFlow()

    private val _isMarkdownEditMode = MutableStateFlow(false)
    val isMarkdownEditMode: StateFlow<Boolean> = _isMarkdownEditMode.asStateFlow()

    private val _subfolderName = MutableStateFlow("")
    val subfolderName: StateFlow<String> = _subfolderName.asStateFlow()

    private val _hasProjectSummary = MutableStateFlow(false)
    val hasProjectSummary: StateFlow<Boolean> = _hasProjectSummary.asStateFlow()

    private val _summaryGenerating = MutableStateFlow(false)
    val summaryGenerating: StateFlow<Boolean> = _summaryGenerating.asStateFlow()

    private val _summaryFeedback = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val summaryFeedback: SharedFlow<String> = _summaryFeedback.asSharedFlow()

    private val _consoleErrors = MutableStateFlow<List<String>>(emptyList())
    val consoleErrors: StateFlow<List<String>> = _consoleErrors.asStateFlow()

    private val _initialBuildSent = MutableStateFlow(
        WorkshopProjectPreferences.isInitialBuildSent(ctx, subfolderId),
    )

    private val _projectPhase = MutableStateFlow(
        WorkshopProjectPreferences.getProjectPhase(ctx, subfolderId),
    )
    val projectPhase: StateFlow<WorkshopProjectPhase> = _projectPhase.asStateFlow()

    /** Fast path: prefs → StateFlows only (safe on main thread). */
    private fun syncPhaseFromPreferences() {
        _projectPhase.value = WorkshopProjectPreferences.getProjectPhase(ctx, subfolderId)
        _designLayoutReady.value = WorkshopProjectPreferences.isDesignLayoutReady(ctx, subfolderId)
        _logicBehaviorReady.value = WorkshopProjectPreferences.isLogicBehaviorReady(ctx, subfolderId)
        pendingDesignReviewAfterBuild =
            WorkshopProjectPreferences.isPendingDesignReviewAfterBuild(ctx, subfolderId)
        pendingLogicReviewAfterBuild =
            WorkshopProjectPreferences.isPendingLogicReviewAfterBuild(ctx, subfolderId)
    }

    /** Reload phase from prefs, then reconcile build progress from disk off the main thread. */
    fun refreshProjectPhase() {
        syncPhaseFromPreferences()
        viewModelScope.launch {
            refreshProjectSummaryState()
            reconcileBuildPhaseFromDisk()
        }
    }

    private suspend fun refreshProjectSummaryState() {
        val sf = withContext(Dispatchers.IO) { db.subfolderDao().getById(subfolderId) }
        _hasProjectSummary.value = !sf?.projectSummary.isNullOrBlank()
    }

    private suspend fun reconcileBuildPhaseFromDisk() {
        val list = files.value
        val phase = _projectPhase.value
        val designReadyFlag = _designLayoutReady.value
        val logicReadyFlag = _logicBehaviorReady.value

        val designInferred = withContext(Dispatchers.IO) {
            phase == WorkshopProjectPhase.DESIGN_BUILD &&
                pendingDesignReviewAfterBuild &&
                !designReadyFlag &&
                inferDesignLayoutReadyFromFiles(list)
        }
        val logicInferred = withContext(Dispatchers.IO) {
            phase == WorkshopProjectPhase.LOGIC_BUILD &&
                pendingLogicReviewAfterBuild &&
                !logicReadyFlag &&
                inferLogicBehaviorReadyFromFiles(list)
        }

        if (designInferred) {
            markDesignLayoutReady()
        } else {
            reconcileDesignPhaseAfterLayoutReady()
        }
        if (logicInferred) {
            markLogicBehaviorReady()
        } else {
            reconcileLogicPhaseAfterBehaviorReady()
        }
    }

    private fun markDesignLayoutReady() {
        if (!_designLayoutReady.value) {
            WorkshopProjectPreferences.setDesignLayoutReady(ctx, subfolderId, true)
            _designLayoutReady.value = true
        }
        reconcileDesignPhaseAfterLayoutReady()
    }

    /**
     * "Design ready" = the design runtime files changed since **Build design** was kicked off.
     * Falls back to the legacy scaffold heuristic only for in-flight builds started before baselines
     * existed (no baseline recorded).
     */
    private fun inferDesignLayoutReadyFromFiles(list: List<FileReference>): Boolean {
        val baseline = WorkshopProjectPreferences.getDesignBuildBaseline(ctx, subfolderId)
        if (baseline.isNotBlank()) {
            return computeRuntimeDigest(list, DESIGN_RUNTIME_FILES) != baseline
        }
        fun read(ref: FileReference?) =
            ref?.let { runCatching { File(it.filePath).readText() }.getOrDefault("") }.orEmpty()
        val title = _subfolderName.value.ifBlank { "Project" }
        val indexScaffold = FolderRepository.WORKSHOP_INDEX_HTML_SCAFFOLD
            .replace("{{TITLE}}", title)
            .trim()
        val styleScaffold = FolderRepository.WORKSHOP_STYLE_CSS_SCAFFOLD.trimIndent().trim()
        val scriptScaffold = FolderRepository.WORKSHOP_SCRIPT_JS_SCAFFOLD.trimIndent().trim()
        val index = list.firstOrNull { it.fileName.equals("index.html", ignoreCase = true) }
        val style = list.firstOrNull { it.fileName.equals("style.css", ignoreCase = true) }
        val script = list.firstOrNull { it.fileName.equals("script.js", ignoreCase = true) }
        return read(index).trim() != indexScaffold ||
            read(style).trim() != styleScaffold ||
            read(script).trim() != scriptScaffold
    }

    private fun reconcileDesignPhaseAfterLayoutReady() {
        if (_projectPhase.value != WorkshopProjectPhase.DESIGN_BUILD || !_designLayoutReady.value) return
        pendingDesignReviewAfterBuild = false
        WorkshopProjectPreferences.setPendingDesignReviewAfterBuild(ctx, subfolderId, false)
        setProjectPhase(WorkshopProjectPhase.DESIGN_REVIEW)
        WorkshopProjectPreferences.setEidosModeOverride(ctx, subfolderId, WorkshopEidosMode.EDIT)
    }

    private fun markLogicBehaviorReady() {
        if (!_logicBehaviorReady.value) {
            WorkshopProjectPreferences.setLogicBehaviorReady(ctx, subfolderId, true)
            _logicBehaviorReady.value = true
        }
        reconcileLogicPhaseAfterBehaviorReady()
    }

    /**
     * "Logic ready" = the logic runtime files (script.js/bridge.js) changed since **Build logic** was
     * kicked off. Falls back to the legacy scaffold heuristic only when no baseline was recorded.
     */
    private fun inferLogicBehaviorReadyFromFiles(list: List<FileReference>): Boolean {
        val baseline = WorkshopProjectPreferences.getLogicBuildBaseline(ctx, subfolderId)
        if (baseline.isNotBlank()) {
            return computeRuntimeDigest(list, LOGIC_RUNTIME_FILES) != baseline
        }
        fun read(ref: FileReference?) =
            ref?.let { runCatching { File(it.filePath).readText() }.getOrDefault("") }.orEmpty()
        val bridgeScaffold = FolderRepository.WORKSHOP_BRIDGE_JS_SCAFFOLD.trimIndent().trim()
        val scriptScaffold = FolderRepository.WORKSHOP_SCRIPT_JS_SCAFFOLD.trimIndent().trim()
        val bridge = list.firstOrNull { it.fileName.equals("bridge.js", ignoreCase = true) }
        val script = list.firstOrNull { it.fileName.equals("script.js", ignoreCase = true) }
        val bridgeText = read(bridge).trim()
        val scriptText = read(script).trim()
        if (bridgeText != bridgeScaffold) return true
        return scriptText != scriptScaffold && scriptText.length > scriptScaffold.length + 300
    }

    /** SHA-256 over the given runtime files (in-memory content for the open file), name-tagged. */
    private fun computeRuntimeDigest(list: List<FileReference>, fileNames: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (name in fileNames) {
            val ref = list.firstOrNull { it.fileName.equals(name, ignoreCase = true) }
            val text = when {
                ref == null -> ""
                ref.id == _currentFileId.value -> _fileContent.value
                else -> runCatching { File(ref.filePath).readText() }.getOrDefault("")
            }
            md.update(name.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(text.toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun reconcileLogicPhaseAfterBehaviorReady() {
        if (_projectPhase.value != WorkshopProjectPhase.LOGIC_BUILD || !_logicBehaviorReady.value) return
        pendingLogicReviewAfterBuild = false
        WorkshopProjectPreferences.setPendingLogicReviewAfterBuild(ctx, subfolderId, false)
        setProjectPhase(WorkshopProjectPhase.LOGIC_REVIEW)
        WorkshopProjectPreferences.setEidosModeOverride(ctx, subfolderId, WorkshopEidosMode.EDIT)
    }

    /**
     * Accept design → Logic build, only from DESIGN_REVIEW. Called when the design doc-align finishes
     * (or is skipped). Resets the logic-build readiness so a fresh Build logic cycle starts clean.
     */
    private fun advanceToLogicBuildAfterDesignAlign() {
        if (_projectPhase.value != WorkshopProjectPhase.DESIGN_REVIEW) return
        WorkshopProjectPreferences.setLogicBehaviorReady(ctx, subfolderId, false)
        _logicBehaviorReady.value = false
        setProjectPhase(WorkshopProjectPhase.LOGIC_BUILD)
        WorkshopProjectPreferences.setEidosModeOverride(ctx, subfolderId, WorkshopEidosMode.EDIT)
    }

    fun setProjectPhase(phase: WorkshopProjectPhase) {
        WorkshopProjectPreferences.setProjectPhase(ctx, subfolderId, phase)
        _projectPhase.value = phase
        if (phase != WorkshopProjectPhase.UPDATE) {
            WorkshopProjectPreferences.setUpdateSection(ctx, subfolderId, null)
        }
    }

    private val _lastCodeSyncDigest = MutableStateFlow(
        WorkshopProjectPreferences.getDocDigestAtLastCodeSync(ctx, subfolderId),
    )

    private var pendingDesignReviewAfterBuild =
        WorkshopProjectPreferences.isPendingDesignReviewAfterBuild(ctx, subfolderId)
    private var pendingLogicReviewAfterBuild =
        WorkshopProjectPreferences.isPendingLogicReviewAfterBuild(ctx, subfolderId)
    private var pendingDocAlignTelemetry = false

    private fun isPendingFinishUpdate(): Boolean =
        WorkshopProjectPreferences.isPendingFinishUpdate(ctx, subfolderId)

    private val _acceptUpdateFinishPending = MutableStateFlow(
        WorkshopProjectPreferences.isPendingFinishUpdate(ctx, subfolderId),
    )

    /** True after the user tapped Accept update once — finish is armed until cancel or complete. */
    val acceptUpdateFinishPending: StateFlow<Boolean> = _acceptUpdateFinishPending.asStateFlow()

    private fun refreshAcceptUpdateFinishPending() {
        _acceptUpdateFinishPending.value = isPendingFinishUpdate()
    }

    private fun setPendingFinishUpdate(pending: Boolean) {
        WorkshopProjectPreferences.setPendingFinishUpdate(ctx, subfolderId, pending)
        refreshAcceptUpdateFinishPending()
    }

    private fun isPendingUpdateAwaitingAlign(): Boolean =
        WorkshopProjectPreferences.isPendingUpdateAwaitingAlign(ctx, subfolderId)

    private fun setPendingUpdateAwaitingAlign(pending: Boolean) {
        WorkshopProjectPreferences.setPendingUpdateAwaitingAlign(ctx, subfolderId, pending)
        refreshAcceptUpdateFinishPending()
    }

    private fun clearPendingUpdateFinishFlags() {
        WorkshopProjectPreferences.setPendingFinishUpdate(ctx, subfolderId, false)
        setPendingUpdateAwaitingAlign(false)
        WorkshopProjectPreferences.setPendingUpdateDocAlignDone(ctx, subfolderId, false)
        refreshAcceptUpdateFinishPending()
    }

    /** Undo an accidental Accept update tap — stay in Update/edit without doc sync or Complete. */
    fun cancelPendingAcceptUpdate() {
        clearPendingUpdateFinishFlags()
        viewModelScope.launch {
            _summaryFeedback.emit(
                "Cancelled finish update — keep editing. Tap Accept update when you actually want to sync specs and return to Complete.",
            )
        }
    }

    private val _designLayoutReady = MutableStateFlow(
        WorkshopProjectPreferences.isDesignLayoutReady(ctx, subfolderId),
    )

    private val _logicBehaviorReady = MutableStateFlow(
        WorkshopProjectPreferences.isLogicBehaviorReady(ctx, subfolderId),
    )

    private val _persistenceFinishGate = MutableStateFlow<PersistenceFinishGate?>(null)
    val persistenceFinishGate: StateFlow<PersistenceFinishGate?> = _persistenceFinishGate.asStateFlow()

    fun dismissPersistenceFinishGate() {
        _persistenceFinishGate.value = null
    }

    init {
        syncPhaseFromPreferences()
        refreshAcceptUpdateFinishPending()
        viewModelScope.launch {
            val sf = db.subfolderDao().getById(subfolderId)
            _subfolderName.value = sf?.name ?: ""
            _hasProjectSummary.value = !sf?.projectSummary.isNullOrBlank()
            reconcileBuildPhaseFromDisk()
            recoverStuckPendingAccept()
        }
        viewModelScope.launch {
            files.collect { list ->
                if (list.isEmpty()) return@collect
                repairWorkshopFilePaths(list)
                promoteImportedWorkshopPhaseIfNeeded(list)
                _needsDesktopFileRestore.value = WorkshopDiskCheck.needsRestoreFromDesktop(ctx, subfolderId)
                if (WorkshopProjectPreferences.isInitialBuildSent(ctx, subfolderId) &&
                    WorkshopProjectPreferences.getDocDigestAtLastCodeSync(ctx, subfolderId).isEmpty()
                ) {
                    val digest = withContext(Dispatchers.IO) {
                        computeWorkshopMdDigest(list, _currentFileId.value, _fileContent.value)
                    }
                    WorkshopProjectPreferences.setDocDigestAtLastCodeSync(ctx, subfolderId, digest)
                    _lastCodeSyncDigest.value = digest
                }
                if (_currentFileId.value == null &&
                    _projectPhase.value != WorkshopProjectPhase.INTAKE &&
                    _projectPhase.value != WorkshopProjectPhase.SPEC_REVIEW &&
                    _projectPhase.value != WorkshopProjectPhase.DESIGN_REVIEW &&
                    _projectPhase.value != WorkshopProjectPhase.LOGIC_REVIEW &&
                    _projectPhase.value != WorkshopProjectPhase.UPDATE
                ) {
                    val readme = list.firstOrNull { it.fileName.equals("README.md", ignoreCase = true) }
                    openFile((readme ?: list.first()).id)
                }
                reconcileBuildPhaseFromDisk()
            }
        }
        // When the user finishes accepting diffs after Accept update, return to COMPLETE.
        viewModelScope.launch {
            pendingChangeCount
                .drop(1)
                .collect { count ->
                    if (count == 0) {
                        tryCompletePendingUpdate()
                    }
                }
        }
    }

    private suspend fun repairWorkshopFilePaths(list: List<FileReference>) {
        list.forEach { ref ->
            val canonical = SyncFilePathResolver.resolve(ctx, db, subfolderId, ref.fileName)
            if (canonical.isNotEmpty() && ref.filePath != canonical) {
                db.fileReferenceDao().insert(ref.copy(filePath = canonical))
            }
        }
    }

    /**
     * Re-imported projects get a new local subfolder id after permanent delete + pull, so
     * [WorkshopProjectPreferences] defaults to INTAKE even when desktop already shipped a panel.
     */
    private suspend fun promoteImportedWorkshopPhaseIfNeeded(list: List<FileReference>) {
        if (_projectPhase.value != WorkshopProjectPhase.INTAKE) return
        val sf = db.subfolderDao().getById(subfolderId) ?: return
        val looksComplete = list.any { it.fileName.equals("index.html", ignoreCase = true) } &&
            list.count { it.fileName.endsWith(".md", ignoreCase = true) } >= 3
        val syncedPanel = !sf.projectSummary.isNullOrBlank() ||
            sf.originDeviceId.orEmpty().startsWith("desktop")
        if (!looksComplete || !syncedPanel) return
        WorkshopProjectPreferences.setProjectPhase(ctx, subfolderId, WorkshopProjectPhase.COMPLETE)
        WorkshopProjectPreferences.setInitialBuildSent(ctx, subfolderId, true)
        _projectPhase.value = WorkshopProjectPhase.COMPLETE
        _initialBuildSent.value = true
        val readme = list.firstOrNull { it.fileName.equals("README.md", ignoreCase = true) }
        if (_currentFileId.value == null) {
            openFile((readme ?: list.first()).id)
        }
    }

    fun openFile(fileId: Long) {
        _currentFileId.value = fileId
        _isPreviewMode.value = false
        _isMarkdownEditMode.value = false
        viewModelScope.launch {
            val ref = db.fileReferenceDao().getById(fileId) ?: return@launch
            val canonical = SyncFilePathResolver.resolve(ctx, db, subfolderId, ref.fileName)
            val path = if (canonical.isNotEmpty()) {
                if (ref.filePath != canonical) {
                    db.fileReferenceDao().insert(ref.copy(filePath = canonical))
                }
                canonical
            } else {
                ref.filePath
            }
            val file = File(path)
            file.parentFile?.mkdirs()
            if (!file.isFile) {
                file.writeText("")
            }
            _fileContent.value = runCatching { file.readText() }.getOrDefault("")
            _isDirty.value = false
        }
    }

    fun openFileByRelativePath(relativePath: String) {
        viewModelScope.launch {
            val normalized = relativePath.trim().trimStart('/').replace('\\', '/')
            if (normalized.isEmpty()) return@launch
            val list = db.fileReferenceDao().getBySubfolderOnce(subfolderId)
            val exact = list.firstOrNull { ref ->
                ref.fileName.replace('\\', '/').equals(normalized, ignoreCase = true)
            }
            val byLeaf = list.firstOrNull { ref ->
                ref.fileName.substringAfterLast('/').equals(
                    normalized.substringAfterLast('/'),
                    ignoreCase = true,
                )
            }
            val match = exact ?: byLeaf ?: return@launch
            openFile(match.id)
        }
    }

    fun onContentChange(content: String) {
        _fileContent.value = content
        _isDirty.value = true
    }

    fun saveCurrentFile() {
        if (!_isDirty.value) return
        val fileId = _currentFileId.value ?: return
        viewModelScope.launch {
            val ref = db.fileReferenceDao().getById(fileId) ?: return@launch
            runCatching { File(ref.filePath).writeText(_fileContent.value) }
                .onSuccess {
                    _isDirty.value = false
                }
        }
    }

    /**
     * Reload workshop files from disk after Eidos (or another external writer) updates them.
     * Skips overwriting in-memory edits the user made while a send was in flight.
     */
    fun reloadFromDiskAfterExternalWrite() {
        viewModelScope.launch {
            _diskRevision.value += 1
            if (_isDirty.value) return@launch
            reloadOpenFileFromDisk()
        }
    }

    /**
     * After the user accepts a Diff Review item, always reload the open file from disk.
     * Clears a dirty editor buffer so accepted proposals are visible and not overwritten on save.
     */
    fun reloadFromDiskAfterDiffAccept() {
        viewModelScope.launch {
            _diskRevision.value += 1
            _isDirty.value = false
            reloadOpenFileFromDisk()
        }
    }

    /**
     * Flush the open file to disk before an Eidos send so tools and Diff Review proposals
     * match what the user sees in the editor.
     */
    suspend fun flushOpenFileToDiskForEidos() {
        flushCurrentFileToDisk()
    }

    private suspend fun reloadOpenFileFromDisk() {
        val fileId = _currentFileId.value ?: return
        val ref = db.fileReferenceDao().getById(fileId) ?: return
        _fileContent.value = runCatching { File(ref.filePath).readText() }.getOrDefault("")
        _isDirty.value = false
    }

    fun toggleMarkdownEditMode() {
        _isMarkdownEditMode.value = !_isMarkdownEditMode.value
    }

    fun openPreview() {
        saveCurrentFile()
        _isPreviewMode.value = true
        _consoleErrors.value = emptyList()
    }

    fun togglePreview() {
        if (_isPreviewMode.value) {
            _isPreviewMode.value = false
        } else {
            saveCurrentFile()
            _isPreviewMode.value = true
            _consoleErrors.value = emptyList()
        }
    }

    fun addConsoleError(message: String) {
        _consoleErrors.value = _consoleErrors.value + message
    }

    fun createFile(fileName: String, initialContent: String = "") {
        viewModelScope.launch {
            val workshopDir = File(ctx.filesDir, "workshop/$subfolderId")
            workshopDir.mkdirs()
            val file = File(workshopDir, fileName)
            file.writeText(initialContent)
            val id = db.fileReferenceDao().insert(
                FileReference(
                    subfolderId = subfolderId,
                    fileName = fileName,
                    fileType = fileName.substringAfterLast('.', "txt"),
                    filePath = file.absolutePath,
                )
            )
            openFile(id)
            _isDirty.value = false
        }
    }

    fun backupWorkshopToPc() {
        if (_workshopBackupBusy.value) return
        viewModelScope.launch {
            _workshopBackupBusy.value = true
            _workshopRestoreInProgress.value = false
            _workshopBackupMessage.value = null
            _workshopBackupProgress.value = null
            when (
                val result = syncFileService.backupWorkshopToDesktop(subfolderId) { progress ->
                    _workshopBackupProgress.value = progress
                }
            ) {
                is SyncCallResult.Failure ->
                    _workshopBackupMessage.value = result.message
                is SyncCallResult.Success -> {
                    val summary = result.value
                    _workshopBackupMessage.value = if (summary.filesUploaded == 0) {
                        "No workshop files on device to sync."
                    } else {
                        "Synced ${summary.filesUploaded} file(s) to desktop " +
                            "(${summary.bytesUploaded / 1024} KB)."
                    }
                }
            }
            _workshopBackupBusy.value = false
            _workshopBackupProgress.value = null
        }
    }

    fun restoreWorkshopFromPc() {
        if (_workshopBackupBusy.value) return
        viewModelScope.launch {
            _workshopBackupBusy.value = true
            _workshopRestoreInProgress.value = true
            _workshopRestoreMessage.value = null
            _workshopBackupProgress.value = null
            when (
                val result = syncFileService.restoreWorkshopFromDesktop(subfolderId) { progress ->
                    _workshopBackupProgress.value = progress
                }
            ) {
                is SyncCallResult.Failure ->
                    _workshopRestoreMessage.value = result.message
                is SyncCallResult.Success -> {
                    val summary = result.value
                    reloadFromDiskAfterDiffAccept()
                    _needsDesktopFileRestore.value = WorkshopDiskCheck.needsRestoreFromDesktop(ctx, subfolderId)
                    _workshopRestoreMessage.value =
                        buildString {
                            append("Synced ${summary.filesUploaded} file(s) from desktop")
                            if (summary.filesSkipped > 0) {
                                append(" (${summary.filesSkipped} missing on PC skipped)")
                            }
                            append(" (${summary.bytesUploaded / 1024} KB).")
                        }
                }
            }
            _workshopBackupBusy.value = false
            _workshopBackupProgress.value = null
        }
    }

    fun clearWorkshopBackupMessage() {
        _workshopBackupMessage.value = null
    }

    fun clearWorkshopRestoreMessage() {
        _workshopRestoreMessage.value = null
    }

    fun deleteFile(fileId: Long) {
        viewModelScope.launch {
            val ref = db.fileReferenceDao().getById(fileId) ?: return@launch
            File(ref.filePath).delete()
            db.fileReferenceDao().deleteById(fileId)
            if (_currentFileId.value == fileId) {
                _currentFileId.value = null
                _fileContent.value = ""
            }
        }
    }

    fun getCompositeHtml(): String {
        val fileList = files.value
        val htmlFile = fileList.firstOrNull {
            it.fileType.equals("html", ignoreCase = true)
        } ?: return "<html><body><p>No HTML file found. Create an index.html to preview.</p></body></html>"

        val htmlContent = readWorkshopFileText(htmlFile)
        val cssContents = fileList
            .filter { it.fileType.equals("css", ignoreCase = true) }
            .map { readWorkshopFileText(it) }
        val jsContents = fileList
            .filter { it.fileType.equals("js", ignoreCase = true) }
            .map { ref -> ref.fileName to readWorkshopFileText(ref) }

        return PanelHtmlComposer.buildCompositeHtml(
            htmlContent = htmlContent,
            cssContents = cssContents,
            jsContents = jsContents,
        )
    }

    private fun readWorkshopFileText(ref: FileReference): String {
        if (_currentFileId.value == ref.id) {
            return _fileContent.value
        }
        return runCatching { File(ref.filePath).readText() }.getOrDefault("")
    }

    fun getPreviewHtmlFileRef(): FileReference? {
        val fileList = files.value
        val current = getCurrentFileRef()
        if (current != null && current.fileType.equals("html", ignoreCase = true)) return current

        return fileList.firstOrNull { it.fileName.equals("index.html", ignoreCase = true) }
            ?: fileList.firstOrNull { it.fileType.equals("html", ignoreCase = true) }
    }

    fun getCurrentFileRef(): FileReference? {
        val id = _currentFileId.value ?: return null
        return files.value.firstOrNull { it.id == id }
    }

    private fun computeWorkshopMdDigest(
        list: List<FileReference>,
        currentId: Long?,
        inMemory: String,
    ): String {
        val mds = list.filter { it.fileType.equals("md", ignoreCase = true) }
            .sortedBy { it.fileName.lowercase(Locale.US) }
        val md = MessageDigest.getInstance("SHA-256")
        for (ref in mds) {
            val text = if (ref.id == currentId) {
                inMemory
            } else {
                runCatching { File(ref.filePath).readText() }.getOrDefault("")
            }
            md.update(ref.fileName.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(text.toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    suspend fun flushCurrentFileToDisk() {
        if (!_isDirty.value) return
        val fileId = _currentFileId.value ?: return
        val ref = db.fileReferenceDao().getById(fileId) ?: return
        withContext(Dispatchers.IO) {
            runCatching { File(ref.filePath).writeText(_fileContent.value) }
                .onSuccess { _isDirty.value = false }
        }
    }

    val canAcceptSpecs: StateFlow<Boolean> = combine(
        files,
        _currentFileId,
        _fileContent,
        _projectPhase,
    ) { list, curId, mem, phase ->
        if (phase != WorkshopProjectPhase.SPEC_REVIEW) return@combine false
        WorkshopSpecValidation.evaluateAcceptReadiness(
            readSpecContentsFromFiles(list, curId, mem),
        ).ready
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val canAcceptDesign: StateFlow<Boolean> = combine(
        _projectPhase,
        _designLayoutReady,
    ) { phase, layoutReady ->
        phase == WorkshopProjectPhase.DESIGN_REVIEW ||
            (phase == WorkshopProjectPhase.DESIGN_BUILD && layoutReady)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val canAcceptLogic: StateFlow<Boolean> = combine(
        _projectPhase,
        _logicBehaviorReady,
    ) { phase, behaviorReady ->
        phase == WorkshopProjectPhase.LOGIC_REVIEW ||
            (phase == WorkshopProjectPhase.LOGIC_BUILD && behaviorReady)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private fun readSpecContentsFromFiles(
        list: List<FileReference>,
        currentId: Long?,
        inMemory: String,
    ): Map<String, String> = list
        .filter { it.fileType.equals("md", ignoreCase = true) }
        .associate { ref ->
            val text = if (ref.id == currentId) {
                inMemory
            } else {
                runCatching { File(ref.filePath).readText() }.getOrDefault("")
            }
            ref.fileName to text
        }

    suspend fun applyPrimaryBuildAction(openEidosSheet: () -> Unit, eidos: EidosChatViewModel) {
        when (_projectPhase.value) {
            WorkshopProjectPhase.INTAKE -> applyGenerateSpecsAction(openEidosSheet, eidos)
            WorkshopProjectPhase.SPEC_REVIEW -> applyAcceptSpecsAction(eidos)
            WorkshopProjectPhase.DESIGN_BUILD -> {
                if (canAcceptDesign.value) {
                    applyAcceptDesignAction(openEidosSheet, eidos)
                } else {
                    applyBuildDesignAction(openEidosSheet, eidos)
                }
            }
            WorkshopProjectPhase.DESIGN_REVIEW -> applyAcceptDesignAction(openEidosSheet, eidos)
            WorkshopProjectPhase.LOGIC_BUILD -> {
                if (canAcceptLogic.value) {
                    applyAcceptLogicAction(openEidosSheet, eidos)
                } else {
                    applyBuildLogicAction(openEidosSheet, eidos)
                }
            }
            WorkshopProjectPhase.LOGIC_REVIEW -> applyAcceptLogicAction(openEidosSheet, eidos)
            WorkshopProjectPhase.UPDATE -> applyAcceptUpdateAction(openEidosSheet, eidos)
            else -> Unit
        }
    }

    /**
     * Enter update/edit from Complete: chat, plan, edit, diff review, then **Accept update**
     * (doc align from code) → Complete again. Repeatable cycle.
     */
    fun enterUpdateEditMode(eidos: EidosChatViewModel) {
        clearPendingUpdateFinishFlags()
        WorkshopProjectPreferences.setUpdateSection(ctx, subfolderId, null)
        setProjectPhase(WorkshopProjectPhase.UPDATE)
        WorkshopProjectPreferences.setEidosModeOverride(ctx, subfolderId, WorkshopEidosMode.CHAT)
        eidos.refreshWorkshopProjectPhase()
        viewModelScope.launch {
            _summaryFeedback.emit(
                "Update/edit — Chat, Plan, or Edit. Review diffs in Diff Review, then Accept update to sync specs and return to Complete.",
            )
        }
    }

    private var updateCompletionEidos: EidosChatViewModel? = null
    private var updateCompletionOpenEidos: (() -> Unit)? = null

    fun bindUpdateCompletion(eidos: EidosChatViewModel, openEidosSheet: () -> Unit) {
        updateCompletionEidos = eidos
        updateCompletionOpenEidos = openEidosSheet
    }

    /** Clears a stuck post-accept state from a prior session. */
    private fun recoverStuckPendingAccept() {
        if (!isPendingFinishUpdate()) return
        if (isPendingUpdateAwaitingAlign()) return
        if (pendingChangeCount.value > 0) return
        if (!WorkshopProjectPreferences.isPendingUpdateDocAlignDone(ctx, subfolderId)) return
        finishUpdateCycleToComplete(updateCompletionEidos)
    }

    private fun finishUpdateCycleToComplete(eidos: EidosChatViewModel?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                WorkshopUpdateCompletion.finishUpdateCycleToComplete(ctx, db, subfolderId)
            }
            syncPhaseFromPreferences()
            eidos?.refreshWorkshopProjectPhase()
        }
    }

    private fun tryCompletePendingUpdate() {
        viewModelScope.launch {
            val wasPending = isPendingFinishUpdate()
            // Do not auto-start doc align — user must tap Accept update again explicitly
            // (see applyAcceptUpdateAction). Accidental first tap only arms this flag.
            if (wasPending &&
                _projectPhase.value == WorkshopProjectPhase.UPDATE &&
                pendingChangeCount.value == 0 &&
                !isPendingUpdateAwaitingAlign() &&
                !WorkshopProjectPreferences.isPendingUpdateDocAlignDone(ctx, subfolderId)
            ) {
                _summaryFeedback.emit(
                    "Ready to finish — tap Accept update again to sync spec docs and return to Complete, " +
                        "or tap Cancel finish to keep editing.",
                )
                return@launch
            }
            withContext(Dispatchers.IO) {
                WorkshopUpdateCompletion.tryFinishPendingUpdate(ctx, db, subfolderId)
            }
            syncPhaseFromPreferences()
            updateCompletionEidos?.refreshWorkshopProjectPhase()
            if (wasPending &&
                _projectPhase.value == WorkshopProjectPhase.COMPLETE &&
                !isPendingFinishUpdate()
            ) {
                _summaryFeedback.emit(
                    "Update complete — panel is Complete. Tap Update when you want to edit again.",
                )
            }
        }
    }

    /**
     * Approval-gate doc align: code → short spec snapshots via Eidos Plan mode.
     * Optionally advances [advancePhase] before the align kickoff.
     *
     * @return true if an Eidos align pass was started; false if skipped (unchanged fingerprints).
     */
    suspend fun alignDocsFromCode(
        scope: WorkshopDocAlignScope,
        openEidosSheet: () -> Unit,
        eidos: EidosChatViewModel,
        advancePhase: WorkshopProjectPhase? = null,
    ): Boolean {
        flushCurrentFileToDisk()
        advancePhase?.let { setProjectPhase(it) }
        eidos.refreshWorkshopProjectPhase()
        val staleSpecs = withContext(Dispatchers.IO) {
            WorkshopDocAlignGate.staleSpecs(
                context = ctx,
                db = db,
                subfolderId = subfolderId,
                scope = scope,
                currentFileId = _currentFileId.value,
                inMemoryContent = _fileContent.value,
            )
        }
        if (staleSpecs.isEmpty()) {
            handleDocAlignSkipped(scope, eidos)
            return false
        }
        val inlinePayload = withContext(Dispatchers.IO) {
            WorkshopDocAlignGate.buildInlinePayload(
                db = db,
                subfolderId = subfolderId,
                scope = scope,
                staleSpecs = staleSpecs,
                currentFileId = _currentFileId.value,
                inMemoryContent = _fileContent.value,
            )
        }
        pendingDocAlignTelemetry = true
        openEidosSheet()
        eidos.sendWorkshopAlignDocsFromCode(subfolderId, scope, staleSpecs, inlinePayload)
        emitDocAlignStartedFeedback(scope)
        return true
    }

    private suspend fun handleDocAlignSkipped(scope: WorkshopDocAlignScope, eidos: EidosChatViewModel) {
        when (scope) {
            WorkshopDocAlignScope.FINISH ->
                _summaryFeedback.emit(
                    "Specs already match code — no doc sync needed. " +
                        panelAvailableOutsideWorkshopMessage(),
                )
            WorkshopDocAlignScope.DESIGN -> {
                advanceToLogicBuildAfterDesignAlign()
                eidos.refreshWorkshopProjectPhase()
                _summaryFeedback.emit(
                    "Specs already match code — no doc sync needed. Tap Build logic when ready.",
                )
            }
            WorkshopDocAlignScope.UPDATE -> {
                WorkshopProjectPreferences.setPendingUpdateAwaitingAlign(ctx, subfolderId, false)
                WorkshopProjectPreferences.setPendingUpdateDocAlignDone(ctx, subfolderId, true)
                finishUpdateCycleToComplete(eidos)
                _summaryFeedback.emit(
                    "Specs already match code — update complete. Panel is Complete.",
                )
            }
        }
    }

    private suspend fun emitDocAlignStartedFeedback(scope: WorkshopDocAlignScope) {
        val message = when (scope) {
            WorkshopDocAlignScope.DESIGN ->
                "Syncing spec docs from code if needed — Eidos will update only files that are out of date."
            WorkshopDocAlignScope.FINISH ->
                if (_projectPhase.value == WorkshopProjectPhase.UPDATE) {
                    "Syncing spec docs from code if needed — then you'll return to Complete."
                } else {
                    "Syncing spec docs from code if needed."
                }
            WorkshopDocAlignScope.UPDATE ->
                "Syncing spec docs from code if needed — then you'll return to Complete."
        }
        _summaryFeedback.emit(message)
    }

    /** Called when an Eidos send finishes while the workshop screen is still composed. */
    fun onEidosSendCompleted(eidos: EidosChatViewModel? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                WorkshopUpdateCompletion.tryFinishPendingUpdate(ctx, db, subfolderId)
            }
            syncPhaseFromPreferences()
            refreshProjectSummaryState()
            // Do not clear the pending build-kickoff flags here — reconcileBuildPhaseFromDisk clears
            // them only when it actually advances to review (once code diverged from the kickoff
            // baseline). Clearing early would suppress the auto-advance.
            reconcileBuildPhaseFromDisk()
            eidos?.refreshWorkshopProjectPhase()
            if (isPendingFinishUpdate() && pendingChangeCount.value > 0) {
                _summaryFeedback.emit(
                    "Review pending changes — then Accept update will sync spec docs and return to Complete.",
                )
            }
        }
    }

    private suspend fun applyGenerateSpecsAction(openEidosSheet: () -> Unit, eidos: EidosChatViewModel) {
        flushCurrentFileToDisk()
        val summary = eidos.buildWorkshopIntakeSummary(subfolderId)
        if (summary.isBlank()) {
            _summaryFeedback.emit(
                "Talk with Eidos about what you want to build first, then tap Generate specs again.",
            )
            openEidosSheet()
            return
        }
        WorkshopProjectPreferences.setIntakeSummary(ctx, subfolderId, summary)
        WorkshopProjectPreferences.setPendingProjectSummaryAfterSpecGenerate(ctx, subfolderId, true)
        setProjectPhase(WorkshopProjectPhase.SPEC_REVIEW)
        eidos.refreshWorkshopProjectPhase()
        openEidosSheet()
        eidos.sendWorkshopGenerateSpecsKickoff(subfolderId, summary)
    }

    private suspend fun applyAcceptSpecsAction(eidos: EidosChatViewModel) {
        flushCurrentFileToDisk()
        val readiness = WorkshopSpecValidation.evaluateAcceptReadiness(
            readSpecContentsFromFiles(files.value, _currentFileId.value, _fileContent.value),
        )
        if (!readiness.ready) {
            _summaryFeedback.emit(readiness.message)
            return
        }
        val capNote = if (readiness.capWarnings.isNotEmpty()) {
            "\n\nLength notes:\n${readiness.capWarnings.joinToString("\n")}"
        } else {
            ""
        }
        setProjectPhase(WorkshopProjectPhase.DESIGN_BUILD)
        eidos.refreshWorkshopProjectPhase()
        files.value.firstOrNull { it.fileName.equals("README.md", ignoreCase = true) }?.let { openFile(it.id) }
        _summaryFeedback.emit(
            "Specs accepted. Review README in Docs if you like, then tap Build design when ready.$capNote",
        )
    }

    private suspend fun applyBuildDesignAction(openEidosSheet: () -> Unit, eidos: EidosChatViewModel) {
        flushCurrentFileToDisk()
        pendingDesignReviewAfterBuild = true
        WorkshopProjectPreferences.setPendingDesignReviewAfterBuild(ctx, subfolderId, true)
        WorkshopProjectPreferences.setDesignBuildBaseline(
            ctx,
            subfolderId,
            withContext(Dispatchers.IO) { computeRuntimeDigest(files.value, DESIGN_RUNTIME_FILES) },
        )
        WorkshopProjectPreferences.setEidosModeOverride(ctx, subfolderId, WorkshopEidosMode.BUILD_DESIGN)
        openPreview()
        openEidosSheet()
        eidos.sendWorkshopBuildDesignKickoff(subfolderId)
        _summaryFeedback.emit(
            "Building design — watch Preview. When Eidos finishes, verify every FLOW screen, then tap Accept design.",
        )
    }

    private suspend fun applyAcceptDesignAction(openEidosSheet: () -> Unit, eidos: EidosChatViewModel) {
        flushCurrentFileToDisk()
        // Stay in DESIGN_REVIEW while the align runs; advance to LOGIC_BUILD only when it finishes
        // (WorkshopUpdateCompletion on success, or handleDocAlignSkipped when nothing needs syncing).
        // A cancelled align therefore leaves the phase in DESIGN_REVIEW — no Build-logic skip.
        val started = alignDocsFromCode(
            scope = WorkshopDocAlignScope.DESIGN,
            openEidosSheet = openEidosSheet,
            eidos = eidos,
            advancePhase = null,
        )
        if (started) {
            _summaryFeedback.emit(
                "Design accepted — syncing specs. When Eidos finishes, tap Build logic.",
            )
        }
    }

    private suspend fun applyBuildLogicAction(openEidosSheet: () -> Unit, eidos: EidosChatViewModel) {
        flushCurrentFileToDisk()
        pendingLogicReviewAfterBuild = true
        WorkshopProjectPreferences.setPendingLogicReviewAfterBuild(ctx, subfolderId, true)
        WorkshopProjectPreferences.setLogicBuildBaseline(
            ctx,
            subfolderId,
            withContext(Dispatchers.IO) { computeRuntimeDigest(files.value, LOGIC_RUNTIME_FILES) },
        )
        WorkshopProjectPreferences.setEidosModeOverride(ctx, subfolderId, WorkshopEidosMode.BUILD_LOGIC)
        openPreview()
        openEidosSheet()
        eidos.sendWorkshopBuildLogicKickoff(subfolderId)
        _summaryFeedback.emit(
            "Building panel logic — test in Preview. When Eidos finishes, use Edit or Debug, then tap Accept logic.",
        )
    }

    private fun readProjectFileText(fileName: String): String {
        val ref = files.value.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) } ?: return ""
        return if (ref.id == _currentFileId.value) {
            _fileContent.value
        } else {
            runCatching { File(ref.filePath).readText() }.getOrDefault("")
        }
    }

    private suspend fun evaluatePersistenceFinishGate(): PersistenceFinishGate? {
        flushCurrentFileToDisk()
        val evaluation = withContext(Dispatchers.IO) {
            PanelPlatformSpec.evaluatePersistenceForFinish(
                scriptJs = readProjectFileText("script.js"),
                bridgeJs = readProjectFileText("bridge.js"),
                featuresMd = readProjectFileText("FEATURES.md"),
            )
        }
        return if (evaluation.shouldBlockFinish) {
            PersistenceFinishGate(warnings = evaluation.warningMessages)
        } else {
            null
        }
    }

    fun confirmAcceptLogicDespitePersistenceWarnings(
        openEidosSheet: () -> Unit,
        eidos: EidosChatViewModel,
    ) {
        viewModelScope.launch {
            Log.w(TAG, "Accept logic: persistence gate overridden subfolderId=$subfolderId")
            _persistenceFinishGate.value = null
            applyAcceptLogicActionInternal(openEidosSheet, eidos)
        }
    }

    private suspend fun applyAcceptLogicAction(openEidosSheet: () -> Unit, eidos: EidosChatViewModel) {
        val gate = evaluatePersistenceFinishGate()
        if (gate != null) {
            _persistenceFinishGate.value = gate
            return
        }
        applyAcceptLogicActionInternal(openEidosSheet, eidos)
    }

    private suspend fun applyAcceptLogicActionInternal(openEidosSheet: () -> Unit, eidos: EidosChatViewModel) {
        flushCurrentFileToDisk()
        setProjectPhase(WorkshopProjectPhase.COMPLETE)
        withContext(Dispatchers.IO) {
            PanelReleaseStore.publishFromWorkshop(ctx, db, subfolderId)
        }
        WorkshopProjectPreferences.setEidosModeOverride(ctx, subfolderId, WorkshopEidosMode.EDIT)
        eidos.refreshWorkshopProjectPhase()
        val started = alignDocsFromCode(
            scope = WorkshopDocAlignScope.FINISH,
            openEidosSheet = openEidosSheet,
            eidos = eidos,
            advancePhase = null,
        )
        if (started) {
            _summaryFeedback.emit(
                "Panel build complete. " + panelAvailableOutsideWorkshopMessage(),
            )
        }
    }

    /** Where the user runs a finished panel (not workshop Preview). */
    fun panelAvailableOutsideWorkshopMessage(): String =
        "To run this panel: open **Panel Gallery**, or add it as a custom panel tab in any subfolder. " +
            "To change the project later, tap **Update** in the workshop."

    private suspend fun applyAcceptUpdateAction(openEidosSheet: () -> Unit, eidos: EidosChatViewModel) {
        flushCurrentFileToDisk()
        if (_projectPhase.value != WorkshopProjectPhase.UPDATE) return
        if (pendingChangeCount.value > 0) {
            setPendingFinishUpdate(true)
            _summaryFeedback.emit(
                "Review pending changes on the Diff Review screen first — then tap Accept update again.",
            )
            return
        }
        if (!isPendingFinishUpdate()) {
            setPendingFinishUpdate(true)
            _summaryFeedback.emit(
                "When you're done editing, tap Accept update again to sync spec docs from code and return to Complete.",
            )
            return
        }
        if (WorkshopProjectPreferences.isPendingUpdateDocAlignDone(ctx, subfolderId)) {
            finishUpdateCycleToComplete(eidos)
            _summaryFeedback.emit("Update complete — panel is Complete.")
            return
        }
        if (isPendingUpdateAwaitingAlign()) {
            _summaryFeedback.emit(
                "Doc sync already running in Eidos — wait for it to finish, or tap Skip spec sync on the banner to return to Complete.",
            )
            return
        }
        setPendingUpdateAwaitingAlign(true)
        alignDocsFromCode(
            scope = WorkshopDocAlignScope.UPDATE,
            openEidosSheet = openEidosSheet,
            eidos = eidos,
        )
    }

    /**
     * Escape hatch when Eidos doc-align is stuck (e.g. JSON/tool loops): return to Complete without
     * rewriting spec .md. Clears the finish-update flags so the user can tap **Update** again.
     */
    fun finishUpdateSkippingDocSync(eidos: EidosChatViewModel?) {
        if (_projectPhase.value != WorkshopProjectPhase.UPDATE) return
        if (!isPendingFinishUpdate()) return
        viewModelScope.launch {
            setPendingUpdateAwaitingAlign(false)
            WorkshopProjectPreferences.setPendingUpdateDocAlignDone(ctx, subfolderId, true)
            finishUpdateCycleToComplete(eidos)
            _summaryFeedback.emit(
                "Returned to Complete without spec sync. Tap Update when you want a new edit session.",
            )
        }
    }

    fun generateOrRegenerateProjectSummary() {
        viewModelScope.launch {
            flushCurrentFileToDisk()
            val inMemory = _currentFileId.value?.let { id -> mapOf(id to _fileContent.value) } ?: emptyMap()
            val hadSummary = _hasProjectSummary.value
            _summaryGenerating.value = true
            val result = appRef.contentSummaryService.generateWorkshopProjectSummary(subfolderId, inMemory)
            _summaryGenerating.value = false
            if (result is ContentSummaryResult.Success) {
                _hasProjectSummary.value = true
                WorkshopProjectSummaryAutomation.recordDigestAfterManualGenerate(ctx, db, subfolderId)
            }
            val message = when (result) {
                ContentSummaryResult.Success ->
                    if (hadSummary) "Project summary updated." else "Project summary generated."
                is ContentSummaryResult.Failed -> result.message
            }
            _summaryFeedback.emit(message)
        }
    }

    private companion object {
        const val TAG = "WorkshopEditor"

        /** Runtime files the design build owns (static layout and visuals). */
        val DESIGN_RUNTIME_FILES = listOf("index.html", "style.css", "script.js")

        /** Runtime files the logic build owns (behavior + bridge). */
        val LOGIC_RUNTIME_FILES = listOf("script.js", "bridge.js")
    }
}
