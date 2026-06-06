package com.example.optimalx.data.revision

import com.example.optimalx.data.dao.FileReferenceDao
import com.example.optimalx.data.model.FileReference
import java.io.File

/**
 * Single point where a workshop file write hits the filesystem. Used by both:
 *
 * - **Build-mode auto-accept** in `RoomToolExecutor` (no review queue), and
 * - **`PendingChangeService.accept(...)`** when the user accepts a queued proposal.
 *
 * Every successful write produces a [ContentCheckpoint] so the timeline is
 * continuous regardless of which path applied the change.
 */
class DirectWriteApplier(
    private val fileReferenceDao: FileReferenceDao,
    private val checkpointRepository: CheckpointRepository,
    private val indexer: WorkshopFileIndexer,
    /**
     * Resolves the on-disk path for a workshop file. Production uses
     * `<filesDir>/workshop/<subfolderId>/<fileName>`; tests inject a temp dir.
     */
    private val workshopFilePath: suspend (subfolderId: Long, fileName: String) -> File,
) {

    /**
     * Overwrite an existing workshop file with [content]. Creates a baseline
     * checkpoint for the **prior** on-disk state on the first write since the
     * DIFF_REVIEW system was introduced, then a new checkpoint for [content].
     */
    suspend fun applyWorkshopWrite(
        ref: FileReference,
        content: String,
        author: String = CHECKPOINT_AUTHOR_EIDOS,
        label: String? = null,
        conversationId: Long? = null,
    ): WriteResult {
        val file = File(ref.filePath)
        val priorContent = if (file.exists()) {
            runCatching { file.readText() }.getOrElse { "" }
        } else ""

        checkpointRepository.baselineIfMissing(
            sourceType = SOURCE_TYPE_WORKSHOP_FILE,
            sourceId = ref.id,
            content = priorContent,
        )

        val writeError = runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }.exceptionOrNull()
        if (writeError != null) {
            return WriteResult.Failed(writeError.message ?: "Write failed")
        }

        indexer.reindex(ref, content)

        val cp = checkpointRepository.createCheckpoint(
            sourceType = SOURCE_TYPE_WORKSHOP_FILE,
            sourceId = ref.id,
            content = content,
            author = author,
            label = label,
            conversationId = conversationId,
        )
        return WriteResult.Applied(fileReferenceId = ref.id, checkpointId = cp.id)
    }

    /**
     * Create a new workshop file under [subfolderId]. Inserts the [FileReference],
     * writes the content to disk, baselines, and indexes.
     */
    suspend fun applyWorkshopCreate(
        subfolderId: Long,
        fileName: String,
        content: String,
        author: String = CHECKPOINT_AUTHOR_EIDOS,
        label: String? = "Initial create",
        conversationId: Long? = null,
    ): WriteResult {
        val target = workshopFilePath(subfolderId, fileName)
        val writeError = runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content)
        }.exceptionOrNull()
        if (writeError != null) {
            return WriteResult.Failed(writeError.message ?: "Write failed")
        }

        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "txt")
        val refId = fileReferenceDao.insert(
            FileReference(
                subfolderId = subfolderId,
                fileName = fileName,
                fileType = ext,
                filePath = target.absolutePath,
            )
        )
        val ref = fileReferenceDao.getById(refId)
            ?: return WriteResult.Failed("FileReference vanished after insert (id=$refId)")

        indexer.reindex(ref, content)

        val cp = checkpointRepository.baselineIfMissing(
            sourceType = SOURCE_TYPE_WORKSHOP_FILE,
            sourceId = ref.id,
            content = content,
            author = author,
            label = label,
            conversationId = conversationId,
        )
        return WriteResult.Applied(fileReferenceId = ref.id, checkpointId = cp.id)
    }
}
