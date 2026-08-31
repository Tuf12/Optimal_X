package com.example.optimalx.ui.workshop.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.model.PendingChangeItem
import com.example.optimalx.data.model.PendingChangeSet
import com.example.optimalx.data.revision.AcceptResult
import com.example.optimalx.data.revision.CheckpointRepository
import com.example.optimalx.data.revision.DirectWriteApplier
import com.example.optimalx.data.revision.PENDING_ITEM_STATUS_PENDING
import com.example.optimalx.data.revision.PendingChangeService
import com.example.optimalx.data.revision.RejectResult
import com.example.optimalx.data.revision.NoteIndexer
import com.example.optimalx.data.revision.SCOPE_SUBFOLDER
import com.example.optimalx.data.revision.WorkshopFileIndexer
import com.example.optimalx.data.semantic.SemanticObjectType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Backs [DiffReviewScreen] for the workshop project [subfolderId]. Owns the per-item
 * Accept/Reject calls and surfaces user-visible feedback messages.
 *
 * State is in Room — the queue is shared with [com.example.optimalx.data.eidos.RoomToolExecutor]
 * (which proposes items) and [com.example.optimalx.ui.workshop.WorkshopEditorViewModel]
 * (which observes accepts to reload the on-disk buffer). All three components construct
 * their own [PendingChangeService] from the same DAOs; service instances are stateless.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiffReviewViewModel(
    app: Application,
    val scopeType: String,
    val subfolderId: Long,
) : AndroidViewModel(app) {

    private val appRef = app as OptimalXApplication
    private val db = appRef.database
    private val pendingDao = db.pendingChangeDao()
    private val fileReferenceDao = db.fileReferenceDao()
    private val checkpointRepository = CheckpointRepository(
        checkpointDao = db.contentCheckpointDao(),
        patchDao = db.contentPatchDao(),
        pendingChangeDao = db.pendingChangeDao(),
    )
    private val noteIndexer = NoteIndexer { noteSubfolderId ->
        val note = db.noteDao().getBySubfolderOnce(noteSubfolderId) ?: return@NoteIndexer
        if (note.aiBlind) {
            appRef.semanticIndexer.deleteObject(SemanticObjectType.NOTE, noteSubfolderId)
        } else {
            appRef.semanticChunkBuilder.indexNote(appRef.semanticIndexer, noteSubfolderId)
        }
        appRef.semanticSyncService.requestSync("note_diff_accept:$noteSubfolderId")
    }
    private val directWriteApplier = DirectWriteApplier(
        fileReferenceDao = fileReferenceDao,
        checkpointRepository = checkpointRepository,
        indexer = WorkshopFileIndexer { ref, content ->
            appRef.semanticChunkBuilder.indexFile(appRef.semanticIndexer, ref, content)
        },
        noteDao = db.noteDao(),
        noteIndexer = noteIndexer,
        workshopFilePath = { subId, fileName ->
            val dir = File(appRef.filesDir, "workshop/$subId").also { it.mkdirs() }
            File(dir, fileName)
        },
    )
    private val service = PendingChangeService(
        pendingDao = pendingDao,
        fileReferenceDao = fileReferenceDao,
        noteDao = db.noteDao(),
        checkpointRepository = checkpointRepository,
        directWriteApplier = directWriteApplier,
    )

    /** Open pending change set for this scope, or null if none. */
    val openSet: StateFlow<PendingChangeSet?> = pendingDao
        .observeOpenSetForScope(
            scopeType = scopeType,
            scopeId = subfolderId,
        )
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isNoteScope: Boolean get() = scopeType == SCOPE_SUBFOLDER

    /** All items in the open set (pending + accepted + rejected). Sorted oldest-first. */
    val items: StateFlow<List<PendingChangeItem>> = openSet
        .flatMapLatest { set ->
            if (set == null) flowOf(emptyList()) else pendingDao.observeItems(set.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Number of pending-status items in the open set. */
    val pendingCount: StateFlow<Int> = items
        .map { list -> list.count { it.status == PENDING_ITEM_STATUS_PENDING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _feedback = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val feedback: SharedFlow<String> = _feedback.asSharedFlow()

    private val _staleAcceptPrompt = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Item id that hit ConcurrentChange on Accept — UI should offer Dismiss vs Leave it. */
    val staleAcceptPrompt: SharedFlow<Long> = _staleAcceptPrompt.asSharedFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun acceptItem(itemId: Long) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val result = service.accept(itemId)) {
                    is AcceptResult.ConcurrentChange -> _staleAcceptPrompt.tryEmit(itemId)
                    else -> emitFeedback(result)
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun dismissItem(itemId: Long) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                when (service.dismiss(itemId)) {
                    RejectResult.Ok -> _feedback.tryEmit("Stale review dismissed — current content kept")
                    is RejectResult.Failed -> _feedback.tryEmit("Dismiss failed")
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun rejectItem(itemId: Long) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                emitFeedback(service.reject(itemId))
            } finally {
                _busy.value = false
            }
        }
    }

    fun acceptAll() {
        val setId = openSet.value?.id ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val results = service.acceptAll(setId)
                val ok = results.count { it is AcceptResult.Applied }
                val conflicts = results.count { it is AcceptResult.ConcurrentChange }
                val failed = results.count { it is AcceptResult.Failed }
                val parts = buildList {
                    if (ok > 0) add("$ok applied")
                    if (conflicts > 0) add("$conflicts blocked by working-copy changes")
                    if (failed > 0) add("$failed failed")
                }
                _feedback.tryEmit(
                    if (parts.isEmpty()) "Nothing to accept"
                    else "Accept all: ${parts.joinToString(", ")}"
                )
            } finally {
                _busy.value = false
            }
        }
    }

    fun rejectAll() {
        val setId = openSet.value?.id ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                when (service.rejectAll(setId)) {
                    RejectResult.Ok -> _feedback.tryEmit("All pending items rejected")
                    is RejectResult.Failed -> _feedback.tryEmit("Reject all failed")
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private fun emitFeedback(result: AcceptResult) {
        val msg = when (result) {
            is AcceptResult.Applied -> "Change applied"
            is AcceptResult.ConcurrentChange ->
                "Working copy changed since proposal — choose Dismiss or Leave it"
            is AcceptResult.Failed -> "Accept failed: ${result.message}"
        }
        _feedback.tryEmit(msg)
    }

    private fun emitFeedback(result: RejectResult) {
        val msg = when (result) {
            RejectResult.Ok -> "Change rejected"
            is RejectResult.Failed -> "Reject failed: ${result.message}"
        }
        _feedback.tryEmit(msg)
    }
}
