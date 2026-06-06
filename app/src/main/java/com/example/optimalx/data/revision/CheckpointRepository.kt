package com.example.optimalx.data.revision

import com.example.optimalx.data.dao.ContentCheckpointDao
import com.example.optimalx.data.dao.ContentPatchDao
import com.example.optimalx.data.model.ContentCheckpoint
import com.example.optimalx.data.model.ContentPatch

/**
 * Manages the sparse checkpoint + forward-patch timeline that backs DIFF_REVIEW
 * restore-to-here and the history drawer.
 *
 * Retention: the oldest non-baseline checkpoints are pruned once the per-source
 * count exceeds [MAX_CHECKPOINTS_PER_SOURCE]. Baseline (sequence = 0) is always
 * kept so the timeline has a stable anchor.
 */
class CheckpointRepository(
    private val checkpointDao: ContentCheckpointDao,
    private val patchDao: ContentPatchDao,
) {

    companion object {
        const val MAX_CHECKPOINTS_PER_SOURCE: Int = 5
    }

    /**
     * Insert a sequence-0 checkpoint for [sourceType] / [sourceId] if no
     * checkpoints exist yet. Returns the new or existing baseline.
     */
    suspend fun baselineIfMissing(
        sourceType: String,
        sourceId: Long,
        content: String,
        author: String = CHECKPOINT_AUTHOR_SYSTEM,
        label: String? = "baseline",
        conversationId: Long? = null,
    ): ContentCheckpoint {
        val existing = checkpointDao.getLatest(sourceType, sourceId)
        if (existing != null) return existing
        return insertCheckpoint(
            sourceType = sourceType,
            sourceId = sourceId,
            sequence = 0,
            content = content,
            author = author,
            label = label,
            conversationId = conversationId,
        )
    }

    /**
     * Snapshot of on-disk content at Diff Review proposal time. Reuses the latest
     * checkpoint when its body already matches [workingCopy]; otherwise records a
     * new checkpoint so Accept's concurrency guard compares against proposal-time disk.
     */
    suspend fun ensureProposalBaseline(
        sourceType: String,
        sourceId: Long,
        workingCopy: String,
        conversationId: Long? = null,
    ): ContentCheckpoint {
        val workingHash = ContentDiff.sha256Hex(workingCopy)
        val latest = checkpointDao.getLatest(sourceType, sourceId)
        if (latest != null && latest.contentHash == workingHash) {
            return latest
        }
        return createCheckpoint(
            sourceType = sourceType,
            sourceId = sourceId,
            content = workingCopy,
            author = CHECKPOINT_AUTHOR_SYSTEM,
            label = "Proposal baseline",
            conversationId = conversationId,
        )
    }

    /**
     * Insert a new checkpoint above the current latest. If a prior checkpoint
     * exists, a forward unified-diff patch is stored from `prior → new`. Pruning
     * runs after insert to keep the count within [MAX_CHECKPOINTS_PER_SOURCE].
     */
    suspend fun createCheckpoint(
        sourceType: String,
        sourceId: Long,
        content: String,
        author: String,
        label: String? = null,
        conversationId: Long? = null,
    ): ContentCheckpoint {
        val prior = checkpointDao.getLatest(sourceType, sourceId)
        val nextSequence = (prior?.sequence ?: -1) + 1
        val cp = insertCheckpoint(
            sourceType = sourceType,
            sourceId = sourceId,
            sequence = nextSequence,
            content = content,
            author = author,
            label = label,
            conversationId = conversationId,
        )

        if (prior != null && prior.contentHash != cp.contentHash) {
            val diff = ContentDiff.unifiedDiff(prior.contentBlob, content)
            if (diff.isNotEmpty()) {
                patchDao.insert(
                    ContentPatch(
                        sourceType = sourceType,
                        sourceId = sourceId,
                        fromCheckpointId = prior.id,
                        toCheckpointId = cp.id,
                        unifiedDiff = diff,
                    )
                )
            }
        }

        pruneRetention(sourceType, sourceId)
        return cp
    }

    suspend fun getById(id: Long): ContentCheckpoint? = checkpointDao.getById(id)

    suspend fun getLatest(sourceType: String, sourceId: Long): ContentCheckpoint? =
        checkpointDao.getLatest(sourceType, sourceId)

    suspend fun listForSource(sourceType: String, sourceId: Long): List<ContentCheckpoint> =
        checkpointDao.listForSource(sourceType, sourceId)

    // ── Internals ────────────────────────────────────────────────────────────

    private suspend fun insertCheckpoint(
        sourceType: String,
        sourceId: Long,
        sequence: Int,
        content: String,
        author: String,
        label: String?,
        conversationId: Long?,
    ): ContentCheckpoint {
        val hash = ContentDiff.sha256Hex(content)
        val now = System.currentTimeMillis()
        val toInsert = ContentCheckpoint(
            sourceType = sourceType,
            sourceId = sourceId,
            sequence = sequence,
            contentBlob = content,
            contentHash = hash,
            author = author,
            label = label,
            conversationId = conversationId,
            createdAt = now,
        )
        val id = checkpointDao.insert(toInsert)
        return toInsert.copy(id = id)
    }

    private suspend fun pruneRetention(sourceType: String, sourceId: Long) {
        val count = checkpointDao.count(sourceType, sourceId)
        if (count <= MAX_CHECKPOINTS_PER_SOURCE) return
        val toRemove = count - MAX_CHECKPOINTS_PER_SOURCE
        val candidates = checkpointDao.listPruneCandidates(sourceType, sourceId, toRemove)
        for (cp in candidates) {
            // Drop the patches referencing this checkpoint first; FK cascade also
            // covers this but explicit deletion keeps behavior consistent across
            // SQLite versions where FK enforcement is opt-in.
            patchDao.deleteByCheckpoint(cp.id)
            checkpointDao.deleteById(cp.id)
        }
    }
}
