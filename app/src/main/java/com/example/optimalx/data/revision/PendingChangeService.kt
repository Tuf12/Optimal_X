package com.example.optimalx.data.revision

import com.example.optimalx.data.dao.FileReferenceDao
import com.example.optimalx.data.dao.NoteDao
import com.example.optimalx.data.dao.PendingChangeDao
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.PendingChangeItem
import com.example.optimalx.data.model.PendingChangeSet
import java.io.File

/**
 * Routes workshop file write/create proposals into the pending review queue and
 * applies user accept/reject decisions. The actual disk write on accept goes
 * through [DirectWriteApplier] so the side-effects (disk + reindex + checkpoint)
 * are identical to the build-mode auto-accept path.
 *
 * In v1 the only `sourceType` values handled are [SOURCE_TYPE_WORKSHOP_FILE]
 * handled are [SOURCE_TYPE_WORKSHOP_FILE], [SOURCE_TYPE_WORKSHOP_NEW_FILE], and
 * [SOURCE_TYPE_NOTE] (subfolder note body).
 */
class PendingChangeService(
    private val pendingDao: PendingChangeDao,
    private val fileReferenceDao: FileReferenceDao,
    private val noteDao: NoteDao? = null,
    private val checkpointRepository: CheckpointRepository,
    private val directWriteApplier: DirectWriteApplier,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    // ── Propose ──────────────────────────────────────────────────────────────

    /**
     * Queue an overwrite proposal for an existing workshop file. Reads the
     * current on-disk content to compute the diff and to capture a baseline
     * checkpoint for the concurrency guard on Accept.
     *
     * If [proposedContent] is byte-equal to the working copy, returns
     * [ProposeResult.NoChange] without touching the queue.
     */
    suspend fun proposeWorkshopFile(
        fileReferenceId: Long,
        conversationId: Long?,
        proposedContent: String,
    ): ProposeResult {
        val ref = fileReferenceDao.getById(fileReferenceId)
            ?: return ProposeResult.Failed("FileReference not found: $fileReferenceId")
        val diskContent = readDiskContent(ref)
        val effectiveWorking = effectiveWorkingContentForFile(ref)
        if (proposedContent == effectiveWorking) {
            return ProposeResult.NoChange("Proposed content matches working copy")
        }

        val baseline = checkpointRepository.ensureProposalBaseline(
            sourceType = SOURCE_TYPE_WORKSHOP_FILE,
            sourceId = ref.id,
            workingCopy = diskContent,
            conversationId = conversationId,
        )

        val diff = ContentDiff.unifiedDiff(diskContent, proposedContent)
        val proposedHash = ContentDiff.sha256Hex(proposedContent)

        val setId = ensureOpenSet(
            scopeType = SCOPE_WORKSHOP_PROJECT,
            scopeId = ref.subfolderId,
            conversationId = conversationId,
        )
        // Supersede a prior open proposal targeting the same file in this set.
        val priorItem = pendingDao.findOpenItemForTarget(setId, SOURCE_TYPE_WORKSHOP_FILE, ref.id)
        val supersededPriorItem = priorItem != null
        priorItem?.let { pendingDao.deleteItem(it.id) }

        val now = clock()
        val itemId = pendingDao.insertItem(
            PendingChangeItem(
                changeSetId = setId,
                sourceType = SOURCE_TYPE_WORKSHOP_FILE,
                sourceId = ref.id,
                baseCheckpointId = baseline.id,
                proposedContent = proposedContent,
                proposedHash = proposedHash,
                unifiedDiff = diff,
                status = PENDING_ITEM_STATUS_PENDING,
                fileName = ref.fileName,
                isNewFile = false,
                createdAt = now,
                updatedAt = now,
            )
        )
        touchSet(setId, now)
        val pendingCount = pendingDao.countPending(setId)
        return ProposeResult.Queued(
            setId = setId,
            itemId = itemId,
            pendingCountInSet = pendingCount,
            supersededPriorItem = supersededPriorItem,
        )
    }

    /**
     * Queue a new-file proposal under [subfolderId]. The file is not created on
     * disk until [accept] runs.
     */
    suspend fun proposeWorkshopCreate(
        subfolderId: Long,
        conversationId: Long?,
        fileName: String,
        content: String,
    ): ProposeResult {
        if (fileName.isBlank()) return ProposeResult.Failed("fileName is required")
        if (fileName.contains('/') || fileName.contains('\\')) {
            return ProposeResult.Failed("Invalid fileName")
        }

        // Reject if a real file with this name already exists.
        val existing = fileReferenceDao.getBySubfolderOnce(subfolderId)
            .firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }
        if (existing != null) {
            return ProposeResult.Failed(
                "File already exists: $fileName — use proposeWorkshopFile with fileReferenceId=${existing.id}",
            )
        }

        val diff = ContentDiff.unifiedDiff("", content)
        val proposedHash = ContentDiff.sha256Hex(content)

        val setId = ensureOpenSet(
            scopeType = SCOPE_WORKSHOP_PROJECT,
            scopeId = subfolderId,
            conversationId = conversationId,
        )
        // Supersede a prior open create proposal with the same fileName.
        val priorCreate = pendingDao.listItems(setId)
            .firstOrNull {
                it.status == PENDING_ITEM_STATUS_PENDING &&
                    it.isNewFile &&
                    it.fileName.equals(fileName, ignoreCase = true)
            }
        val supersededPriorItem = priorCreate != null
        priorCreate?.let { pendingDao.deleteItem(it.id) }

        val now = clock()
        val itemId = pendingDao.insertItem(
            PendingChangeItem(
                changeSetId = setId,
                sourceType = SOURCE_TYPE_WORKSHOP_NEW_FILE,
                sourceId = subfolderId,
                baseCheckpointId = null,
                proposedContent = content,
                proposedHash = proposedHash,
                unifiedDiff = diff,
                status = PENDING_ITEM_STATUS_PENDING,
                fileName = fileName,
                isNewFile = true,
                createdAt = now,
                updatedAt = now,
            )
        )
        touchSet(setId, now)
        val pendingCount = pendingDao.countPending(setId)
        return ProposeResult.Queued(
            setId = setId,
            itemId = itemId,
            pendingCountInSet = pendingCount,
            supersededPriorItem = supersededPriorItem,
        )
    }

    /**
     * Queue a note-body overwrite proposal for [subfolderId]. Diff is always
     * stored body → [proposedContent]; one open row per note in the set.
     */
    suspend fun proposeNote(
        subfolderId: Long,
        conversationId: Long?,
        proposedContent: String,
    ): ProposeResult {
        val dao = noteDao ?: return ProposeResult.Failed("NoteDao not configured")
        val note = dao.getBySubfolderOnce(subfolderId)
            ?: return ProposeResult.Failed("Note not found for subfolderId=$subfolderId")
        val diskContent = note.content
        val effectiveWorking = effectiveWorkingContentForNote(subfolderId)
        if (proposedContent == effectiveWorking) {
            return ProposeResult.NoChange("Proposed content matches working copy")
        }

        val baseline = checkpointRepository.ensureProposalBaseline(
            sourceType = SOURCE_TYPE_NOTE,
            sourceId = subfolderId,
            workingCopy = diskContent,
            conversationId = conversationId,
        )

        val diff = ContentDiff.unifiedDiff(diskContent, proposedContent)
        val proposedHash = ContentDiff.sha256Hex(proposedContent)

        val setId = ensureOpenSet(
            scopeType = SCOPE_SUBFOLDER,
            scopeId = subfolderId,
            conversationId = conversationId,
        )
        val priorItem = pendingDao.findOpenItemForTarget(setId, SOURCE_TYPE_NOTE, subfolderId)
        val supersededPriorItem = priorItem != null
        priorItem?.let { pendingDao.deleteItem(it.id) }

        val now = clock()
        val itemId = pendingDao.insertItem(
            PendingChangeItem(
                changeSetId = setId,
                sourceType = SOURCE_TYPE_NOTE,
                sourceId = subfolderId,
                baseCheckpointId = baseline.id,
                proposedContent = proposedContent,
                proposedHash = proposedHash,
                unifiedDiff = diff,
                status = PENDING_ITEM_STATUS_PENDING,
                fileName = "Note",
                isNewFile = false,
                createdAt = now,
                updatedAt = now,
            )
        )
        touchSet(setId, now)
        val pendingCount = pendingDao.countPending(setId)
        return ProposeResult.Queued(
            setId = setId,
            itemId = itemId,
            pendingCountInSet = pendingCount,
            supersededPriorItem = supersededPriorItem,
        )
    }

    // ── Accept / Reject ──────────────────────────────────────────────────────

    /**
     * Applies the proposal to disk via [DirectWriteApplier], which reindexes the file
     * into the semantic store ([WorkshopFileIndexer]) for `search_semantic`.
     */
    suspend fun accept(itemId: Long): AcceptResult {
        val item = pendingDao.getItem(itemId)
            ?: return AcceptResult.Failed(itemId, "Item not found")
        if (item.status != PENDING_ITEM_STATUS_PENDING) {
            return AcceptResult.Failed(itemId, "Item not pending (status=${item.status})")
        }
        return when (item.sourceType) {
            SOURCE_TYPE_WORKSHOP_FILE -> acceptWorkshopWrite(item)
            SOURCE_TYPE_WORKSHOP_NEW_FILE -> acceptWorkshopCreate(item)
            SOURCE_TYPE_NOTE -> acceptNoteWrite(item)
            else -> AcceptResult.Failed(itemId, "Unsupported sourceType: ${item.sourceType}")
        }
    }

    suspend fun acceptAll(setId: Long): List<AcceptResult> {
        val pending = pendingDao.listItems(setId).filter { it.status == PENDING_ITEM_STATUS_PENDING }
        return pending.map { accept(it.id) }
    }

    suspend fun reject(itemId: Long): RejectResult {
        val item = pendingDao.getItem(itemId)
            ?: return RejectResult.Failed("Item not found")
        if (item.status != PENDING_ITEM_STATUS_PENDING) {
            return RejectResult.Failed("Item not pending (status=${item.status})")
        }
        val now = clock()
        pendingDao.updateItem(item.copy(status = PENDING_ITEM_STATUS_REJECTED, updatedAt = now))
        refreshSetStatus(item.changeSetId, now)
        return RejectResult.Ok
    }

    /**
     * Clear a pending Diff Review row without changing the current note/file.
     * Used when Accept hits [AcceptResult.ConcurrentChange] and the user chooses
     * Dismiss (keep current content). On mobile this currently matches [reject];
     * keep the API so write-through workshop undo can diverge later if needed.
     */
    suspend fun dismiss(itemId: Long): RejectResult = reject(itemId)

    suspend fun rejectAll(setId: Long): RejectResult {
        val pending = pendingDao.listItems(setId).filter { it.status == PENDING_ITEM_STATUS_PENDING }
        for (item in pending) reject(item.id)
        return RejectResult.Ok
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    suspend fun openSetFor(scopeType: String, scopeId: Long): PendingChangeSet? =
        pendingDao.findOpenSetForScope(scopeType, scopeId)

    suspend fun listItems(setId: Long): List<PendingChangeItem> = pendingDao.listItems(setId)

    /**
     * Content Eidos should treat as "current" for reads and chained replace_string edits.
     * During Diff Review, an open pending proposal for [ref] overrides disk until accept.
     */
    suspend fun effectiveWorkingContentForFile(ref: FileReference): String {
        val disk = readDiskContent(ref)
        val set = pendingDao.findOpenSetForScope(SCOPE_WORKSHOP_PROJECT, ref.subfolderId) ?: return disk
        val pending = pendingDao.findOpenItemForTarget(set.id, SOURCE_TYPE_WORKSHOP_FILE, ref.id)
        return pending?.proposedContent ?: disk
    }

    /**
     * Note body Eidos should treat as current for reads and chained replace_string edits.
     * An open pending proposal overrides the stored note until accept.
     */
    suspend fun effectiveWorkingContentForNote(subfolderId: Long): String {
        val dao = noteDao ?: return ""
        val disk = dao.getBySubfolderOnce(subfolderId)?.content.orEmpty()
        val set = pendingDao.findOpenSetForScope(SCOPE_SUBFOLDER, subfolderId) ?: return disk
        val pending = pendingDao.findOpenItemForTarget(set.id, SOURCE_TYPE_NOTE, subfolderId)
        return pending?.proposedContent ?: disk
    }

    suspend fun formatNotePendingQueueLine(subfolderId: Long): String? {
        val set = pendingDao.findOpenSetForScope(SCOPE_SUBFOLDER, subfolderId) ?: return null
        val pending = pendingDao.listItems(set.id).filter { it.status == PENDING_ITEM_STATUS_PENDING }
        if (pending.isEmpty()) return null
        val count = pending.size
        val label = if (count == 1) "1 pending note change" else "$count pending note changes"
        return " Diff Review queue ($label)."
    }

    /** JSON summary for [workshop_list_pending_review] and write-tool feedback. */
    suspend fun formatPendingQueueReport(subfolderId: Long): String {
        val set = pendingDao.findOpenSetForScope(SCOPE_WORKSHOP_PROJECT, subfolderId) ?: run {
            return """{"pendingCount":0,"files":[],"note":"No open Diff Review queue for this project."}"""
        }
        val pending = pendingDao.listItems(set.id).filter { it.status == PENDING_ITEM_STATUS_PENDING }
        val files = pending.mapNotNull { it.fileName }.sorted()
        val fileLines = files.joinToString(",") { "\"$it\"" }
        return buildString {
            append("""{"pendingCount":${pending.size},"files":[""")
            append(fileLines)
            append("""],"policy":"One Diff Review row per file; repeated edits to the same file update that row (diff is always disk → latest proposal). Multiple files → multiple rows."""")
        }
    }

    suspend fun formatPendingQueueLine(subfolderId: Long): String? {
        val set = pendingDao.findOpenSetForScope(SCOPE_WORKSHOP_PROJECT, subfolderId) ?: return null
        val pending = pendingDao.listItems(set.id).filter { it.status == PENDING_ITEM_STATUS_PENDING }
        if (pending.isEmpty()) return null
        val names = pending.mapNotNull { it.fileName }.sorted().joinToString(", ")
        val count = pending.size
        val label = if (count == 1) "1 pending item" else "$count pending items"
        return " Diff Review queue ($label): $names."
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun readDiskContent(ref: FileReference): String {
        val file = File(ref.filePath)
        return if (file.exists()) {
            runCatching { file.readText() }.getOrElse { "" }
        } else {
            ""
        }
    }

    private suspend fun acceptWorkshopWrite(item: PendingChangeItem): AcceptResult {
        val ref = fileReferenceDao.getById(item.sourceId)
            ?: return AcceptResult.Failed(item.id, "FileReference not found: ${item.sourceId}")

        val file = File(ref.filePath)
        val currentContent = if (file.exists()) {
            runCatching { file.readText() }.getOrElse { "" }
        } else ""
        val currentHash = ContentDiff.sha256Hex(currentContent)

        if (item.baseCheckpointId != null) {
            val baseline = checkpointRepository.getById(item.baseCheckpointId)
            if (baseline != null && baseline.contentHash != currentHash) {
                return AcceptResult.ConcurrentChange(
                    itemId = item.id,
                    expectedHash = baseline.contentHash,
                    actualHash = currentHash,
                )
            }
        }

        val conversationId = pendingDao.getSet(item.changeSetId)?.conversationId
        val write = directWriteApplier.applyWorkshopWrite(
            ref = ref,
            content = item.proposedContent,
            author = CHECKPOINT_AUTHOR_EIDOS,
            label = "Accepted proposal",
            conversationId = conversationId,
        )
        return when (write) {
            is WriteResult.Applied -> {
                markItemAccepted(item)
                AcceptResult.Applied(item.id, write.checkpointId, write.fileReferenceId)
            }
            is WriteResult.Failed -> AcceptResult.Failed(item.id, write.message)
        }
    }

    private suspend fun acceptWorkshopCreate(item: PendingChangeItem): AcceptResult {
        val fileName = item.fileName
            ?: return AcceptResult.Failed(item.id, "fileName missing on create proposal")
        val existing = fileReferenceDao.getBySubfolderOnce(item.sourceId)
            .firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }
        if (existing != null) {
            return AcceptResult.Failed(item.id, "File now exists at accept time: $fileName")
        }

        val conversationId = pendingDao.getSet(item.changeSetId)?.conversationId
        val write = directWriteApplier.applyWorkshopCreate(
            subfolderId = item.sourceId,
            fileName = fileName,
            content = item.proposedContent,
            author = CHECKPOINT_AUTHOR_EIDOS,
            label = "Initial create (accepted)",
            conversationId = conversationId,
        )
        return when (write) {
            is WriteResult.Applied -> {
                markItemAccepted(item)
                AcceptResult.Applied(item.id, write.checkpointId, write.fileReferenceId)
            }
            is WriteResult.Failed -> AcceptResult.Failed(item.id, write.message)
        }
    }

    private suspend fun acceptNoteWrite(item: PendingChangeItem): AcceptResult {
        val dao = noteDao
            ?: return AcceptResult.Failed(item.id, "NoteDao not configured")
        val note = dao.getBySubfolderOnce(item.sourceId)
            ?: return AcceptResult.Failed(item.id, "Note not found: ${item.sourceId}")
        val currentContent = note.content
        val currentHash = ContentDiff.sha256Hex(currentContent)

        if (item.baseCheckpointId != null) {
            val baseline = checkpointRepository.getById(item.baseCheckpointId)
            if (baseline != null && baseline.contentHash != currentHash) {
                return AcceptResult.ConcurrentChange(
                    itemId = item.id,
                    expectedHash = baseline.contentHash,
                    actualHash = currentHash,
                )
            }
        }

        val conversationId = pendingDao.getSet(item.changeSetId)?.conversationId
        val write = directWriteApplier.applyNoteWrite(
            subfolderId = item.sourceId,
            content = item.proposedContent,
            author = CHECKPOINT_AUTHOR_EIDOS,
            label = "Accepted proposal",
            conversationId = conversationId,
        )
        return when (write) {
            is WriteResult.Applied -> {
                markItemAccepted(item)
                AcceptResult.Applied(item.id, write.checkpointId, write.fileReferenceId)
            }
            is WriteResult.Failed -> AcceptResult.Failed(item.id, write.message)
        }
    }

    private suspend fun markItemAccepted(item: PendingChangeItem) {
        val now = clock()
        pendingDao.updateItem(item.copy(status = PENDING_ITEM_STATUS_ACCEPTED, updatedAt = now))
        refreshSetStatus(item.changeSetId, now)
    }

    private suspend fun ensureOpenSet(scopeType: String, scopeId: Long, conversationId: Long?): Long {
        pendingDao.findOpenSetForScope(scopeType, scopeId)?.let { return it.id }
        val now = clock()
        return pendingDao.insertSet(
            PendingChangeSet(
                scopeType = scopeType,
                scopeId = scopeId,
                conversationId = conversationId,
                status = PENDING_SET_STATUS_OPEN,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private suspend fun touchSet(setId: Long, now: Long) {
        val set = pendingDao.getSet(setId) ?: return
        if (set.updatedAt != now) {
            pendingDao.updateSet(set.copy(updatedAt = now))
        }
    }

    private suspend fun refreshSetStatus(setId: Long, now: Long) {
        val set = pendingDao.getSet(setId) ?: return
        val items = pendingDao.listItems(setId)
        val pending = items.count { it.status == PENDING_ITEM_STATUS_PENDING }
        val accepted = items.count { it.status == PENDING_ITEM_STATUS_ACCEPTED }
        val rejected = items.count { it.status == PENDING_ITEM_STATUS_REJECTED }
        // Keep the set `open` while any item is still pending so observers keyed on
        // status = 'open' (Diff Review, editor badge, ensureOpenSet) stay wired.
        // `partial` is only for fully resolved sets with a mix of accepted and rejected.
        val newStatus = when {
            pending > 0 -> PENDING_SET_STATUS_OPEN
            accepted > 0 && rejected > 0 -> PENDING_SET_STATUS_PARTIAL
            accepted > 0 -> PENDING_SET_STATUS_ACCEPTED
            rejected > 0 -> PENDING_SET_STATUS_REJECTED
            else -> set.status
        }
        if (newStatus != set.status || set.updatedAt != now) {
            pendingDao.updateSet(set.copy(status = newStatus, updatedAt = now))
        }
    }
}
