package com.example.optimalx.data.revision

import com.example.optimalx.data.dao.NoteDao
import com.example.optimalx.data.dao.PendingChangeDao
import com.example.optimalx.data.eidos.NoteWriteMerge

/**
 * Entry point for Eidos note writes (`write_note`, `edit_note_section`).
 *
 * - **Empty stored body, no pending proposal** → [DirectWriteApplier.applyNoteWrite] (auto-apply).
 * - **Append, patch, or any change while body exists / pending** → [PendingChangeService.proposeNote].
 */
class NoteWriteRouter(
    private val noteDao: NoteDao,
    private val pendingDao: PendingChangeDao,
    private val directWriteApplier: DirectWriteApplier,
    private val pendingChangeService: PendingChangeService,
    private val checkpointRepository: CheckpointRepository,
    private val conversationIdProvider: () -> Long? = { null },
) {

    suspend fun applyOrProposeMerge(
        subfolderId: Long,
        additionMarkdown: String,
    ): NoteWriteOutcome {
        val current = pendingChangeService.effectiveWorkingContentForNote(subfolderId)
        val merged = NoteWriteMerge.merge(current, additionMarkdown)
        return applyOrProposeContent(
            subfolderId = subfolderId,
            proposedContent = merged.mergedMarkdown,
            label = if (merged.appended) null else "Note created",
            successMessage = merged.successMessage,
        )
    }

    suspend fun applyOrProposeContent(
        subfolderId: Long,
        proposedContent: String,
        label: String? = null,
        successMessage: String? = null,
    ): NoteWriteOutcome {
        val note = noteDao.getBySubfolderOnce(subfolderId)
            ?: return NoteWriteOutcome.Failed("Note not found")
        autoCommitUserEditsIfDirty(subfolderId, note.content)
        val diskContent = note.content
        val effective = pendingChangeService.effectiveWorkingContentForNote(subfolderId)
        if (proposedContent == effective) {
            return NoteWriteOutcome.NoChange(
                description = "No changes needed for note",
                subfolderId = subfolderId,
            )
        }

        if (shouldAutoApply(subfolderId, diskContent)) {
            return when (val r = directWriteApplier.applyNoteWrite(
                subfolderId = subfolderId,
                content = proposedContent,
                author = CHECKPOINT_AUTHOR_EIDOS,
                label = label,
                conversationId = conversationIdProvider(),
            )) {
                is WriteResult.Applied -> NoteWriteOutcome.Written(
                    subfolderId = subfolderId,
                    checkpointId = r.checkpointId,
                    description = successMessage ?: "Note updated",
                )
                is WriteResult.Failed -> NoteWriteOutcome.Failed(r.message)
            }
        }

        return when (val r = pendingChangeService.proposeNote(
            subfolderId = subfolderId,
            conversationId = conversationIdProvider(),
            proposedContent = proposedContent,
        )) {
            is ProposeResult.Queued -> NoteWriteOutcome.Queued(
                setId = r.setId,
                itemId = r.itemId,
                subfolderId = subfolderId,
                description = queuedReviewDescription(
                    pendingCount = r.pendingCountInSet,
                    superseded = r.supersededPriorItem,
                    queueLine = pendingChangeService.formatNotePendingQueueLine(subfolderId),
                ),
            )
            is ProposeResult.NoChange -> NoteWriteOutcome.NoChange(
                description = "No changes needed for note",
                subfolderId = subfolderId,
            )
            is ProposeResult.Failed -> NoteWriteOutcome.Failed(r.message)
        }
    }

    private suspend fun shouldAutoApply(subfolderId: Long, diskContent: String): Boolean {
        if (diskContent.isNotBlank()) return false
        val set = pendingDao.findOpenSetForScope(SCOPE_SUBFOLDER, subfolderId) ?: return true
        val pending = pendingDao.findOpenItemForTarget(set.id, SOURCE_TYPE_NOTE, subfolderId)
        return pending == null
    }

    private suspend fun autoCommitUserEditsIfDirty(subfolderId: Long, workingCopy: String) {
        checkpointRepository.commitWorkingCopyIfDirty(
            sourceType = SOURCE_TYPE_NOTE,
            sourceId = subfolderId,
            workingCopy = workingCopy,
            author = CHECKPOINT_AUTHOR_USER,
            label = CHECKPOINT_LABEL_BEFORE_EIDOS,
            conversationId = conversationIdProvider(),
        )
    }

    companion object {
        const val QUEUED_REVIEW_ASSISTANT_HINT: String =
            " Diff Review: the user must accept this proposal in the note Diff Review panel " +
                "(editor top bar or chat banner) before it is saved."

        internal fun queuedReviewDescription(
            pendingCount: Int? = null,
            superseded: Boolean = false,
            queueLine: String? = null,
        ): String {
            val prefix = "Note change proposal queued for review."
            val collapseNote = if (superseded) {
                " Replaced the earlier pending proposal for this note."
            } else {
                ""
            }
            val countNote = pendingCount?.let { count ->
                val label = if (count == 1) "1 pending item" else "$count pending items"
                " Diff Review queue: $label total."
            }.orEmpty()
            return prefix + collapseNote + countNote + (queueLine.orEmpty()) + QUEUED_REVIEW_ASSISTANT_HINT
        }
    }
}

/** Outcome of a note write routed through [NoteWriteRouter]. */
sealed interface NoteWriteOutcome {
    data class Written(
        val subfolderId: Long,
        val checkpointId: Long,
        val description: String,
    ) : NoteWriteOutcome

    data class Queued(
        val setId: Long,
        val itemId: Long,
        val subfolderId: Long,
        val description: String,
    ) : NoteWriteOutcome

    data class NoChange(
        val description: String,
        val subfolderId: Long? = null,
    ) : NoteWriteOutcome

    data class Failed(val message: String) : NoteWriteOutcome
}
