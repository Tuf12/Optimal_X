package com.example.optimalx.data.revision

import com.example.optimalx.data.eidos.WorkshopEidosMode
import com.example.optimalx.data.eidos.WorkshopProjectPhase
import com.example.optimalx.data.model.FileReference
import java.io.File

/**
 * Single entry point for all workshop file writes invoked by Eidos tool calls.
 *
 * Consults [WorkshopReviewPolicy] for the current phase + mode, then dispatches:
 *
 * - **Build phases / build-family modes** → [DirectWriteApplier] (auto-accept, disk written).
 * - **Review / edit / debug / update phases** → [PendingChangeService] (queued for review).
 *
 * Tools (`workshop_write_file`, `workshop_create_file`, `workshop_replace_string`)
 * all funnel through here so the policy + checkpoint behavior is identical regardless
 * of which authoring style the LLM chose.
 */
class WorkshopWriteRouter(
    private val directWriteApplier: DirectWriteApplier,
    private val pendingChangeService: PendingChangeService,
    private val phaseProvider: () -> WorkshopProjectPhase?,
    private val modeProvider: () -> WorkshopEidosMode?,
    private val conversationIdProvider: () -> Long? = { null },
) {

    /** Overwrite [ref] with [proposedContent]. */
    suspend fun applyOrProposeWrite(
        ref: FileReference,
        proposedContent: String,
        label: String? = null,
    ): WorkshopWriteOutcome {
        if (shouldReview()) {
            return when (val r = pendingChangeService.proposeWorkshopFile(
                fileReferenceId = ref.id,
                conversationId = conversationIdProvider(),
                proposedContent = proposedContent,
            )) {
                is ProposeResult.Queued -> WorkshopWriteOutcome.Queued(
                    setId = r.setId,
                    itemId = r.itemId,
                    description = queuedReviewDescription(
                        fileName = ref.fileName,
                        pendingCount = r.pendingCountInSet,
                        superseded = r.supersededPriorItem,
                        queueLine = pendingChangeService.formatPendingQueueLine(ref.subfolderId),
                    ),
                )
                is ProposeResult.NoChange -> WorkshopWriteOutcome.NoChange(
                    "No changes needed for ${ref.fileName}",
                )
                is ProposeResult.Failed -> WorkshopWriteOutcome.Failed(r.message)
            }
        }

        // Auto-accept path. No-op detection so identical content doesn't churn checkpoints.
        val current = readFileSafely(File(ref.filePath))
        if (current == proposedContent) {
            return WorkshopWriteOutcome.NoChange("No changes needed for ${ref.fileName}")
        }

        val (author, resolvedLabel) = resolveAutoAcceptAttribution(label)
        return when (val r = directWriteApplier.applyWorkshopWrite(
            ref = ref,
            content = proposedContent,
            author = author,
            label = resolvedLabel,
            conversationId = conversationIdProvider(),
        )) {
            is WriteResult.Applied -> WorkshopWriteOutcome.Written(
                fileReferenceId = r.fileReferenceId,
                checkpointId = r.checkpointId,
                description = "Updated ${ref.fileName} (${proposedContent.length} characters persisted to disk)",
            )
            is WriteResult.Failed -> WorkshopWriteOutcome.Failed(r.message)
        }
    }

    /** Create [fileName] under [subfolderId]. */
    suspend fun applyOrProposeCreate(
        subfolderId: Long,
        fileName: String,
        proposedContent: String,
    ): WorkshopWriteOutcome {
        if (shouldReview()) {
            return when (val r = pendingChangeService.proposeWorkshopCreate(
                subfolderId = subfolderId,
                conversationId = conversationIdProvider(),
                fileName = fileName,
                content = proposedContent,
            )) {
                is ProposeResult.Queued -> WorkshopWriteOutcome.Queued(
                    setId = r.setId,
                    itemId = r.itemId,
                    description = queuedReviewDescription(
                        fileName = fileName,
                        created = true,
                        pendingCount = r.pendingCountInSet,
                        superseded = r.supersededPriorItem,
                        queueLine = pendingChangeService.formatPendingQueueLine(subfolderId),
                    ),
                )
                is ProposeResult.NoChange -> WorkshopWriteOutcome.NoChange(
                    "Empty proposal for $fileName",
                )
                is ProposeResult.Failed -> WorkshopWriteOutcome.Failed(r.message)
            }
        }

        val (author, resolvedLabel) = resolveAutoAcceptAttribution(label = null, defaultLabel = "Initial create")
        return when (val r = directWriteApplier.applyWorkshopCreate(
            subfolderId = subfolderId,
            fileName = fileName,
            content = proposedContent,
            author = author,
            label = resolvedLabel,
            conversationId = conversationIdProvider(),
        )) {
            is WriteResult.Applied -> WorkshopWriteOutcome.Written(
                fileReferenceId = r.fileReferenceId,
                checkpointId = r.checkpointId,
                description = "Created $fileName (fileReferenceId=${r.fileReferenceId})",
            )
            is WriteResult.Failed -> WorkshopWriteOutcome.Failed(r.message)
        }
    }

    private fun shouldReview(): Boolean =
        WorkshopReviewPolicy.shouldReview(phaseProvider(), modeProvider())

    /**
     * Build-mode auto-accept writes carry a `system` author + a `"<phase> build"` label so the
     * history drawer can show clear waypoints the user can roll back to. Outside build phases
     * (rare for the auto-accept path: only PLAN-ish exceptions), the proposal author stays
     * `eidos` and the caller-supplied [label] is preserved.
     */
    private fun resolveAutoAcceptAttribution(
        label: String?,
        defaultLabel: String? = null,
    ): Pair<String, String?> {
        val phase = phaseProvider()
        return when (phase) {
            WorkshopProjectPhase.DESIGN_BUILD ->
                CHECKPOINT_AUTHOR_SYSTEM to (label ?: "Design build")
            WorkshopProjectPhase.LOGIC_BUILD ->
                CHECKPOINT_AUTHOR_SYSTEM to (label ?: "Logic build")
            else ->
                CHECKPOINT_AUTHOR_EIDOS to (label ?: defaultLabel)
        }
    }

    private fun readFileSafely(file: File): String =
        runCatching { if (file.exists()) file.readText() else "" }.getOrElse { "" }

    companion object {
        /** Appended to tool results so the model tells the user about Diff Review. */
        const val QUEUED_REVIEW_ASSISTANT_HINT: String =
            " Diff Review: the user must accept this proposal in the workshop Diff Review panel " +
                "(top bar or chat banner) before it affects Preview or Panel Gallery."

        internal fun queuedReviewDescription(
            fileName: String,
            created: Boolean = false,
            pendingCount: Int? = null,
            superseded: Boolean = false,
            queueLine: String? = null,
        ): String {
            val prefix = if (created) {
                "New file proposal queued for review: $fileName."
            } else {
                "Proposal queued for review: $fileName."
            }
            val collapseNote = if (superseded) {
                " Replaced the earlier pending proposal for this file (still one Diff Review row per file)."
            } else {
                ""
            }
            val countNote = pendingCount?.let { count ->
                val label = if (count == 1) "1 pending item" else "$count pending items"
                " Diff Review queue: $label total — tell the user that number only, not how many tool calls you made."
            }.orEmpty()
            return prefix + collapseNote + countNote + (queueLine.orEmpty()) + QUEUED_REVIEW_ASSISTANT_HINT
        }
    }
}

/** Outcome of a single workshop write/create routed through [WorkshopWriteRouter]. */
sealed interface WorkshopWriteOutcome {
    /** Disk was modified directly (build-mode auto-accept). */
    data class Written(
        val fileReferenceId: Long,
        val checkpointId: Long,
        val description: String,
    ) : WorkshopWriteOutcome

    /** Change queued in the review pipeline. */
    data class Queued(
        val setId: Long,
        val itemId: Long,
        val description: String,
    ) : WorkshopWriteOutcome

    /** Proposed content matched the working copy; nothing applied. */
    data class NoChange(val description: String) : WorkshopWriteOutcome

    data class Failed(val message: String) : WorkshopWriteOutcome
}
