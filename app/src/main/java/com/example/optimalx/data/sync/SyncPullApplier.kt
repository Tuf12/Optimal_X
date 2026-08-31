package com.example.optimalx.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.model.CustomPanelAssignment
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.model.ContentCheckpoint
import com.example.optimalx.data.model.ContentPatch
import com.example.optimalx.data.model.PendingChangeSet
import com.example.optimalx.data.model.PendingChangeItem
import com.example.optimalx.data.model.PanelState
import com.example.optimalx.data.preferences.DumpEditPreferences
import com.example.optimalx.data.preferences.DumpEditState
import com.example.optimalx.data.sync.SyncContentHash.withSyncFields
import java.io.File

class SyncPullApplier(
    private val context: Context,
    private val db: AppDatabase,
) {

    suspend fun applyTier1(payload: SyncTablesPayload): SyncApplyStats {
        val stats = SyncApplyStats()
        db.withTransaction {
            payload.parentFolders.forEach { applyParent(it, stats) }
            payload.subfolders.forEach { applySubfolder(it, stats) }
            payload.notes.forEach { applyNote(it, stats) }
            payload.fileReferences.forEach { applyFileReference(it, stats) }
            payload.dumpEdit?.let { applyDumpEdit(it, stats) }
        }
        return stats
    }

    suspend fun applyTier2(payload: SyncTablesPayload): SyncApplyStats {
        val stats = SyncApplyStats()
        db.withTransaction {
            payload.customPanelAssignments.forEach { applyCustomPanelAssignment(it, stats) }
            payload.conversations.forEach { applyConversation(it, stats) }
            payload.chatMessages.forEach { applyChatMessage(it, stats) }
            payload.contentCheckpoints.forEach { applyContentCheckpoint(it, stats) }
            payload.contentPatches.forEach { applyContentPatch(it, stats) }
            payload.pendingChangeSets.forEach { applyPendingChangeSet(it, stats) }
            payload.pendingChangeItems.forEach { applyPendingChangeItem(it, stats) }
            payload.panelState.forEach { applyPanelState(it, stats) }
        }
        return stats
    }

    suspend fun applyAll(payload: SyncTablesPayload): SyncApplyStats {
        val tier1 = applyTier1(payload)
        val tier2 = applyTier2(payload)
        return SyncApplyStats(
            applied = mergeCountMaps(tier1.applied, tier2.applied),
            skipped = mergeCountMaps(tier1.skipped, tier2.skipped),
            conflicts = (tier1.conflicts + tier2.conflicts).toMutableList(),
        )
    }

    private fun mergeCountMaps(
        a: Map<String, Int>,
        b: Map<String, Int>,
    ): MutableMap<String, Int> {
        val out = a.toMutableMap()
        b.forEach { (k, v) -> out[k] = (out[k] ?: 0) + v }
        return out
    }

    private suspend fun applyParent(row: SyncParentFolderRow, stats: SyncApplyStats) {
        val table = "parentFolders"
        val local = db.parentFolderDao().getByGlobalId(row.globalId)
        if (local == null) {
            db.parentFolderDao().insert(
                ParentFolder(
                    name = row.name,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    sortOrder = row.sortOrder,
                    deletedAt = row.deletedAt,
                    isSystemFolder = row.isSystemFolder,
                    globalId = row.globalId,
                    originDeviceId = row.originDeviceId,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
            return
        }
        if (row.deletedAt != null) {
            val outcome = SyncConflictLogic.tombstoneOutcome(local.deletedAt, row.deletedAt)
            if (outcome == SyncApplyOutcome.APPLIED) {
                db.parentFolderDao().update(local.copy(deletedAt = row.deletedAt, updatedAt = row.updatedAt))
            }
            stats.record(table, outcome, row.globalId)
            return
        }
        val outcome = SyncConflictLogic.compareUpdated(
            localUpdatedAt = local.updatedAt,
            incomingUpdatedAt = row.updatedAt,
            localHash = null,
            incomingHash = null,
            localFingerprint = SyncFingerprints.parentFolder(local),
            incomingFingerprint = SyncFingerprints.parentFolder(row),
        )
        if (outcome == SyncApplyOutcome.APPLIED) {
            db.parentFolderDao().update(
                local.copy(
                    name = row.name,
                    sortOrder = row.sortOrder,
                    deletedAt = row.deletedAt,
                    isSystemFolder = row.isSystemFolder,
                    updatedAt = row.updatedAt,
                    originDeviceId = row.originDeviceId ?: local.originDeviceId,
                ),
            )
        } else if (outcome == SyncApplyOutcome.CONFLICT) {
            stats.record(table, outcome, row.globalId, "equal_timestamp_field_mismatch")
            return
        }
        stats.record(table, outcome, row.globalId)
    }

    private suspend fun applySubfolder(row: SyncSubfolderRow, stats: SyncApplyStats) {
        val table = "subfolders"
        val local = db.subfolderDao().getByGlobalId(row.globalId)
        val parent = db.parentFolderDao().getByGlobalId(row.parentFolderGlobalId)
        if (parent == null) {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId, "missing_parent")
            return
        }
        if (local == null) {
            db.subfolderDao().insert(
                Subfolder(
                    parentFolderId = parent.id,
                    name = row.name,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    sortOrder = row.sortOrder,
                    deletedAt = row.deletedAt,
                    isSystemSubfolder = row.isSystemSubfolder,
                    projectSummary = row.projectSummary,
                    projectSummaryUpdatedAt = row.projectSummaryUpdatedAt,
                    targetPlatform = row.targetPlatform,
                    globalId = row.globalId,
                    originDeviceId = row.originDeviceId,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
            return
        }
        if (row.deletedAt != null) {
            val outcome = SyncConflictLogic.tombstoneOutcome(local.deletedAt, row.deletedAt)
            if (outcome == SyncApplyOutcome.APPLIED) {
                db.subfolderDao().update(local.copy(deletedAt = row.deletedAt, updatedAt = row.updatedAt))
            }
            stats.record(table, outcome, row.globalId)
            return
        }
        val outcome = SyncConflictLogic.compareUpdated(
            localUpdatedAt = local.updatedAt,
            incomingUpdatedAt = row.updatedAt,
            localHash = null,
            incomingHash = null,
            localFingerprint = SyncFingerprints.subfolder(local),
            incomingFingerprint = SyncFingerprints.subfolder(row),
        )
        if (outcome == SyncApplyOutcome.APPLIED) {
            db.subfolderDao().update(
                local.copy(
                    parentFolderId = parent.id,
                    name = row.name,
                    sortOrder = row.sortOrder,
                    deletedAt = row.deletedAt,
                    isSystemSubfolder = row.isSystemSubfolder,
                    projectSummary = row.projectSummary,
                    projectSummaryUpdatedAt = row.projectSummaryUpdatedAt,
                    targetPlatform = row.targetPlatform,
                    updatedAt = row.updatedAt,
                    originDeviceId = row.originDeviceId ?: local.originDeviceId,
                ),
            )
        } else if (outcome == SyncApplyOutcome.CONFLICT) {
            stats.record(table, outcome, row.globalId, "equal_timestamp_field_mismatch")
            return
        }
        stats.record(table, outcome, row.globalId)
    }

    private suspend fun applyNote(row: SyncNoteRow, stats: SyncApplyStats) {
        val table = "notes"
        val local = db.noteDao().getByGlobalId(row.globalId)
        val subfolder = db.subfolderDao().getByGlobalId(row.subfolderGlobalId)
        if (subfolder == null) {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId, "missing_subfolder")
            return
        }
        if (local == null) {
            db.noteDao().insert(
                Note(
                    subfolderId = subfolder.id,
                    content = row.content,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    deletedAt = row.deletedAt,
                    aiLocked = row.aiLocked,
                    aiBlind = row.aiBlind,
                    summary = row.summary,
                    summaryUpdatedAt = row.summaryUpdatedAt,
                    summaryContentWatermark = row.summaryContentWatermark,
                    globalId = row.globalId,
                    originDeviceId = row.originDeviceId,
                    contentHash = row.contentHash.ifBlank { SyncContentHash.noteContentHash(row.content) },
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
            return
        }
        if (row.deletedAt != null) {
            val outcome = SyncConflictLogic.tombstoneOutcome(local.deletedAt, row.deletedAt)
            if (outcome == SyncApplyOutcome.APPLIED) {
                db.noteDao().update(local.copy(deletedAt = row.deletedAt, updatedAt = row.updatedAt))
            }
            stats.record(table, outcome, row.globalId)
            return
        }
        val localHash = SyncFingerprints.noteContentHash(local.contentHash, local.content)
        val incomingHash = SyncFingerprints.noteContentHash(row.contentHash, row.content)
        val outcome = SyncConflictLogic.compareUpdated(
            localUpdatedAt = local.updatedAt,
            incomingUpdatedAt = row.updatedAt,
            localHash = localHash,
            incomingHash = incomingHash,
            localFingerprint = localHash,
            incomingFingerprint = incomingHash,
        )
        if (outcome == SyncApplyOutcome.APPLIED) {
            db.noteDao().update(
                local.withSyncFields(content = row.content, updatedAt = row.updatedAt) {
                    it.copy(
                        subfolderId = subfolder.id,
                        deletedAt = row.deletedAt,
                        aiLocked = row.aiLocked,
                        aiBlind = row.aiBlind,
                        summary = row.summary,
                        summaryUpdatedAt = row.summaryUpdatedAt,
                        summaryContentWatermark = row.summaryContentWatermark,
                        originDeviceId = row.originDeviceId ?: local.originDeviceId,
                    )
                },
            )
        } else if (outcome == SyncApplyOutcome.CONFLICT) {
            stats.record(table, outcome, row.globalId, "equal_timestamp_hash_mismatch")
            return
        }
        stats.record(table, outcome, row.globalId)
    }

    private suspend fun applyFileReference(row: SyncFileReferenceRow, stats: SyncApplyStats) {
        val table = "fileReferences"
        val local = db.fileReferenceDao().getByGlobalId(row.globalId)
        val subfolder = db.subfolderDao().getByGlobalId(row.subfolderGlobalId)
        if (subfolder == null) {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId, "missing_subfolder")
            return
        }
        val localPath = SyncFilePathResolver.resolve(context, db, subfolder.id, row.fileName)
        if (local == null) {
            db.fileReferenceDao().insert(
                FileReference(
                    subfolderId = subfolder.id,
                    fileName = row.fileName,
                    fileType = row.fileType,
                    filePath = localPath,
                    createdAt = row.createdAt,
                    globalId = row.globalId,
                    originDeviceId = row.originDeviceId,
                    metadataJson = row.metadataJson,
                    deletedAt = row.deletedAt,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
            return
        }
        if (row.deletedAt != null) {
            val outcome = SyncConflictLogic.tombstoneOutcome(local.deletedAt, row.deletedAt)
            if (outcome == SyncApplyOutcome.APPLIED) {
                db.fileReferenceDao().update(local.copy(deletedAt = row.deletedAt))
            }
            stats.record(table, outcome, row.globalId)
            return
        }
        val pathNeedsRepair = local.filePath != localPath || !File(local.filePath).isFile
        if (row.createdAt > local.createdAt || pathNeedsRepair || local.deletedAt != null) {
            db.fileReferenceDao().update(
                local.copy(
                    subfolderId = subfolder.id,
                    fileName = row.fileName,
                    fileType = row.fileType,
                    filePath = localPath,
                    createdAt = if (row.createdAt > local.createdAt) row.createdAt else local.createdAt,
                    originDeviceId = row.originDeviceId ?: local.originDeviceId,
                    metadataJson = row.metadataJson ?: local.metadataJson,
                    deletedAt = null,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
        } else {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId)
        }
    }

    private suspend fun applyDumpEdit(row: SyncDumpEditRow, stats: SyncApplyStats) {
        val table = "dumpEdit"
        val local = DumpEditPreferences.readState(context)
        val localHash = SyncFingerprints.noteContentHash(local.contentHash, local.content)
        val incomingHash = SyncFingerprints.noteContentHash(row.contentHash, row.content)
        val outcome = SyncConflictLogic.compareUpdated(
            localUpdatedAt = local.updatedAt,
            incomingUpdatedAt = row.updatedAt,
            localHash = localHash,
            incomingHash = incomingHash,
            localFingerprint = localHash,
            incomingFingerprint = incomingHash,
        )
        if (outcome == SyncApplyOutcome.APPLIED) {
            DumpEditPreferences.restoreState(
                context,
                DumpEditState(
                    content = row.content,
                    aiLocked = row.aiLocked,
                    aiBlind = row.aiBlind,
                    globalId = row.globalId,
                    updatedAt = row.updatedAt,
                    contentHash = row.contentHash.ifBlank { SyncContentHash.sha256Hex(row.content) },
                ),
            )
        } else if (outcome == SyncApplyOutcome.CONFLICT) {
            stats.record(table, outcome, row.globalId, "equal_timestamp_hash_mismatch")
            return
        }
        stats.record(table, outcome, row.globalId)
    }

    private suspend fun applyCustomPanelAssignment(row: SyncCustomPanelAssignmentRow, stats: SyncApplyStats) {
        val table = "customPanelAssignments"
        val local = db.customPanelAssignmentDao().getByGlobalId(row.globalId)
        val workshop = db.subfolderDao().getByGlobalId(row.workshopSubfolderGlobalId)
        val target = db.subfolderDao().getByGlobalId(row.targetSubfolderGlobalId)
        if (workshop == null || target == null) {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId, "missing_subfolder")
            return
        }
        if (local == null) {
            db.customPanelAssignmentDao().insert(
                CustomPanelAssignment(
                    workshopSubfolderId = workshop.id,
                    targetSubfolderId = target.id,
                    panelTitle = row.panelTitle,
                    createdAt = row.createdAt,
                    globalId = row.globalId,
                    originDeviceId = row.originDeviceId,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
            return
        }
        if (row.createdAt > local.createdAt) {
            db.customPanelAssignmentDao().insert(
                local.copy(
                    workshopSubfolderId = workshop.id,
                    targetSubfolderId = target.id,
                    panelTitle = row.panelTitle,
                    createdAt = row.createdAt,
                    originDeviceId = row.originDeviceId ?: local.originDeviceId,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
        } else {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId)
        }
    }

    private suspend fun applyConversation(row: SyncConversationRow, stats: SyncApplyStats) {
        val table = "conversations"
        val local = db.conversationDao().getByGlobalId(row.globalId)
        val parentId = row.parentFolderGlobalId?.let { db.parentFolderDao().getByGlobalId(it)?.id }
        val subfolderId = row.subfolderGlobalId?.let { db.subfolderDao().getByGlobalId(it)?.id }
        val summaryMessageId = row.threadSummaryThroughMessageGlobalId?.let {
            db.chatMessageDao().getByGlobalId(it)?.id
        }
        if (local == null) {
            db.conversationDao().insert(
                Conversation(
                    scopeType = row.scopeType,
                    parentFolderId = parentId,
                    subfolderId = subfolderId,
                    webSearchKey = row.webSearchKey,
                    title = row.title,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    memoryDepth = row.memoryDepth,
                    threadSummary = row.threadSummary,
                    threadSummaryCoversMessageId = summaryMessageId,
                    globalId = row.globalId,
                    originDeviceId = row.originDeviceId,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
            return
        }
        val outcome = SyncConflictLogic.compareUpdated(
            localUpdatedAt = local.updatedAt,
            incomingUpdatedAt = row.updatedAt,
            localHash = null,
            incomingHash = null,
            localFingerprint = SyncFingerprints.conversation(local),
            incomingFingerprint = SyncFingerprints.conversation(row),
        )
        if (outcome == SyncApplyOutcome.APPLIED) {
            db.conversationDao().update(
                local.copy(
                    scopeType = row.scopeType,
                    parentFolderId = parentId,
                    subfolderId = subfolderId,
                    webSearchKey = row.webSearchKey,
                    title = row.title,
                    updatedAt = row.updatedAt,
                    memoryDepth = row.memoryDepth,
                    threadSummary = row.threadSummary,
                    threadSummaryCoversMessageId = summaryMessageId,
                    originDeviceId = row.originDeviceId ?: local.originDeviceId,
                ),
            )
        } else if (outcome == SyncApplyOutcome.CONFLICT) {
            stats.record(table, outcome, row.globalId, "equal_timestamp_field_mismatch")
            return
        }
        stats.record(table, outcome, row.globalId)
    }

    private suspend fun applyChatMessage(row: SyncChatMessageRow, stats: SyncApplyStats) {
        val table = "chatMessages"
        if (db.chatMessageDao().getByGlobalId(row.globalId) != null) {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId)
            return
        }
        val conversation = db.conversationDao().getByGlobalId(row.conversationGlobalId)
        if (conversation == null) {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId, "missing_conversation")
            return
        }
        db.chatMessageDao().insert(
            ChatMessage(
                conversationId = conversation.id,
                role = row.role,
                content = row.content,
                assistantReasoningContent = row.assistantReasoningContent,
                navigationTargetsJson = row.navigationTargetsJson,
                imageAttachmentJson = row.imageAttachmentJson,
                createdAt = row.createdAt,
                globalId = row.globalId,
                originDeviceId = row.originDeviceId,
            ),
        )
        stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
    }

    private suspend fun applyContentCheckpoint(row: SyncContentCheckpointRow, stats: SyncApplyStats) {
        val table = "contentCheckpoints"
        val local = db.contentCheckpointDao().getByGlobalId(row.globalId)
        val sourceId = resolveSourceLocalId(row.sourceType, row.sourceGlobalId)
        if (sourceId == null) {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId, "missing_source")
            return
        }
        val conversationId = row.conversationGlobalId?.let { db.conversationDao().getByGlobalId(it)?.id }
        if (local == null) {
            db.contentCheckpointDao().insert(
                ContentCheckpoint(
                    sourceType = row.sourceType,
                    sourceId = sourceId,
                    sequence = row.sequence,
                    contentBlob = row.contentBlob,
                    contentHash = row.contentHash,
                    author = row.author,
                    label = row.label,
                    conversationId = conversationId,
                    createdAt = row.createdAt,
                    globalId = row.globalId,
                    originDeviceId = row.originDeviceId,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
            return
        }
        val outcome = SyncConflictLogic.compareUpdated(
            localUpdatedAt = local.createdAt,
            incomingUpdatedAt = row.createdAt,
            localHash = local.contentHash,
            incomingHash = row.contentHash,
            localFingerprint = local.contentHash,
            incomingFingerprint = row.contentHash,
        )
        if (outcome == SyncApplyOutcome.APPLIED) {
            db.contentCheckpointDao().update(
                local.copy(
                    sourceType = row.sourceType,
                    sourceId = sourceId,
                    sequence = row.sequence,
                    contentBlob = row.contentBlob,
                    contentHash = row.contentHash,
                    author = row.author,
                    label = row.label,
                    conversationId = conversationId,
                    originDeviceId = row.originDeviceId ?: local.originDeviceId,
                ),
            )
        } else if (outcome == SyncApplyOutcome.CONFLICT) {
            stats.record(table, outcome, row.globalId, "equal_timestamp_hash_mismatch")
            return
        }
        stats.record(table, outcome, row.globalId)
    }

    private suspend fun applyContentPatch(row: SyncContentPatchRow, stats: SyncApplyStats) {
        val table = "contentPatches"
        val local = db.contentPatchDao().getByGlobalId(row.globalId)
        val sourceId = resolveSourceLocalId(row.sourceType, row.sourceGlobalId)
        val fromId = db.contentCheckpointDao().getByGlobalId(row.fromCheckpointGlobalId)?.id
        val toId = db.contentCheckpointDao().getByGlobalId(row.toCheckpointGlobalId)?.id
        if (sourceId == null || fromId == null || toId == null) {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId, "missing_fk")
            return
        }
        if (local == null) {
            db.contentPatchDao().insert(
                ContentPatch(
                    sourceType = row.sourceType,
                    sourceId = sourceId,
                    fromCheckpointId = fromId,
                    toCheckpointId = toId,
                    unifiedDiff = row.unifiedDiff,
                    createdAt = row.createdAt,
                    globalId = row.globalId,
                    originDeviceId = row.originDeviceId,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
            return
        }
        if (row.createdAt > local.createdAt) {
            db.contentPatchDao().update(
                local.copy(
                    sourceType = row.sourceType,
                    sourceId = sourceId,
                    fromCheckpointId = fromId,
                    toCheckpointId = toId,
                    unifiedDiff = row.unifiedDiff,
                    createdAt = row.createdAt,
                    originDeviceId = row.originDeviceId ?: local.originDeviceId,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
        } else {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId)
        }
    }

    private suspend fun applyPendingChangeSet(row: SyncPendingChangeSetRow, stats: SyncApplyStats) {
        val table = "pendingChangeSets"
        val local = db.pendingChangeDao().getSetByGlobalId(row.globalId)
        val scopeId = resolveScopeLocalId(row.scopeType, row.scopeGlobalId)
        if (scopeId == null) {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId, "missing_scope")
            return
        }
        val conversationId = row.conversationGlobalId?.let { db.conversationDao().getByGlobalId(it)?.id }
        if (local == null) {
            db.pendingChangeDao().insertSet(
                PendingChangeSet(
                    scopeType = row.scopeType,
                    scopeId = scopeId,
                    conversationId = conversationId,
                    status = row.status,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    globalId = row.globalId,
                    originDeviceId = row.originDeviceId,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
            return
        }
        val outcome = SyncConflictLogic.compareUpdated(
            localUpdatedAt = local.updatedAt,
            incomingUpdatedAt = row.updatedAt,
            localHash = null,
            incomingHash = null,
            localFingerprint = SyncFingerprints.pendingChangeSet(local),
            incomingFingerprint = SyncFingerprints.pendingChangeSet(row),
        )
        if (outcome == SyncApplyOutcome.APPLIED) {
            db.pendingChangeDao().updateSet(
                local.copy(
                    scopeType = row.scopeType,
                    scopeId = scopeId,
                    conversationId = conversationId,
                    status = row.status,
                    updatedAt = row.updatedAt,
                    originDeviceId = row.originDeviceId ?: local.originDeviceId,
                ),
            )
        } else if (outcome == SyncApplyOutcome.CONFLICT) {
            stats.record(table, outcome, row.globalId, "equal_timestamp_field_mismatch")
            return
        }
        stats.record(table, outcome, row.globalId)
    }

    private suspend fun applyPendingChangeItem(row: SyncPendingChangeItemRow, stats: SyncApplyStats) {
        val table = "pendingChangeItems"
        val local = db.pendingChangeDao().getItemByGlobalId(row.globalId)
        val changeSet = db.pendingChangeDao().getSetByGlobalId(row.changeSetGlobalId)
        val sourceId = resolveSourceLocalId(row.sourceType, row.sourceGlobalId)
        if (changeSet == null || sourceId == null) {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId, "missing_fk")
            return
        }
        val baseCheckpointId = row.baseCheckpointGlobalId?.let {
            db.contentCheckpointDao().getByGlobalId(it)?.id
        }
        if (local == null) {
            db.pendingChangeDao().insertItem(
                PendingChangeItem(
                    changeSetId = changeSet.id,
                    sourceType = row.sourceType,
                    sourceId = sourceId,
                    baseCheckpointId = baseCheckpointId,
                    proposedContent = row.proposedContent,
                    proposedHash = row.proposedHash,
                    unifiedDiff = row.unifiedDiff.orEmpty(),
                    status = row.status,
                    fileName = row.fileName,
                    isNewFile = row.isNewFile,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    globalId = row.globalId,
                    originDeviceId = row.originDeviceId,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
            return
        }
        val outcome = SyncConflictLogic.compareUpdated(
            localUpdatedAt = local.updatedAt,
            incomingUpdatedAt = row.updatedAt,
            localHash = null,
            incomingHash = null,
            localFingerprint = SyncFingerprints.pendingChangeItem(local),
            incomingFingerprint = SyncFingerprints.pendingChangeItem(row),
        )
        if (outcome == SyncApplyOutcome.APPLIED) {
            db.pendingChangeDao().updateItem(
                local.copy(
                    changeSetId = changeSet.id,
                    sourceType = row.sourceType,
                    sourceId = sourceId,
                    baseCheckpointId = baseCheckpointId,
                    proposedContent = row.proposedContent,
                    proposedHash = row.proposedHash,
                    unifiedDiff = row.unifiedDiff.orEmpty(),
                    status = row.status,
                    fileName = row.fileName,
                    isNewFile = row.isNewFile,
                    updatedAt = row.updatedAt,
                    originDeviceId = row.originDeviceId ?: local.originDeviceId,
                ),
            )
        } else if (outcome == SyncApplyOutcome.CONFLICT) {
            stats.record(table, outcome, row.globalId, "equal_timestamp_field_mismatch")
            return
        }
        stats.record(table, outcome, row.globalId)
    }

    private suspend fun applyPanelState(row: SyncPanelStateRow, stats: SyncApplyStats) {
        val table = "panelState"
        val local = db.panelStateDao().getByGlobalId(row.globalId)
        val workshop = db.subfolderDao().getByGlobalId(row.workshopSubfolderGlobalId)
        if (workshop == null) {
            stats.record(table, SyncApplyOutcome.SKIPPED, row.globalId, "missing_subfolder")
            return
        }
        val contentHash = row.contentHash.ifBlank { SyncContentHash.panelStateContentHash(row.stateJson) }
        val localHash = SyncFingerprints.panelStateHash(local?.contentHash.orEmpty(), local?.stateJson.orEmpty())
        val incomingHash = SyncFingerprints.panelStateHash(contentHash, row.stateJson)
        if (local == null) {
            val existingScope = db.panelStateDao().getByWorkshopScope(workshop.id, row.scopeKey)
            if (existingScope != null && existingScope.globalId != row.globalId) {
                val outcome = SyncConflictLogic.compareUpdated(
                    localUpdatedAt = existingScope.updatedAt,
                    incomingUpdatedAt = row.updatedAt,
                    localHash = existingScope.contentHash,
                    incomingHash = contentHash,
                    localFingerprint = existingScope.contentHash,
                    incomingFingerprint = incomingHash,
                )
                if (outcome == SyncApplyOutcome.APPLIED) {
                    db.panelStateDao().upsert(
                        existingScope.copy(
                            stateJson = row.stateJson,
                            updatedAt = row.updatedAt,
                            contentHash = contentHash,
                            originDeviceId = row.originDeviceId ?: existingScope.originDeviceId,
                            globalId = row.globalId,
                        ),
                    )
                } else if (outcome == SyncApplyOutcome.CONFLICT) {
                    stats.record(table, outcome, row.globalId, "equal_timestamp_hash_mismatch")
                    return
                }
                stats.record(table, outcome, row.globalId)
                return
            }
            db.panelStateDao().insert(
                PanelState(
                    workshopSubfolderId = workshop.id,
                    scopeKey = row.scopeKey,
                    stateJson = row.stateJson,
                    updatedAt = row.updatedAt,
                    globalId = row.globalId,
                    originDeviceId = row.originDeviceId,
                    contentHash = contentHash,
                ),
            )
            stats.record(table, SyncApplyOutcome.APPLIED, row.globalId)
            return
        }
        val outcome = SyncConflictLogic.compareUpdated(
            localUpdatedAt = local.updatedAt,
            incomingUpdatedAt = row.updatedAt,
            localHash = local.contentHash,
            incomingHash = contentHash,
            localFingerprint = localHash,
            incomingFingerprint = incomingHash,
        )
        if (outcome == SyncApplyOutcome.APPLIED) {
            db.panelStateDao().upsert(
                local.copy(
                    workshopSubfolderId = workshop.id,
                    scopeKey = row.scopeKey,
                    stateJson = row.stateJson,
                    updatedAt = row.updatedAt,
                    contentHash = contentHash,
                    originDeviceId = row.originDeviceId ?: local.originDeviceId,
                ),
            )
        } else if (outcome == SyncApplyOutcome.CONFLICT) {
            stats.record(table, outcome, row.globalId, "equal_timestamp_hash_mismatch")
            return
        }
        stats.record(table, outcome, row.globalId)
    }

    private suspend fun resolveSourceLocalId(sourceType: String, sourceGlobalId: String): Long? =
        when (sourceType) {
            "workshop_file" -> db.fileReferenceDao().getByGlobalId(sourceGlobalId)?.id
            // workshop_new_file sourceGlobalId is the workshop project subfolder globalId.
            "workshop_new_file" -> db.subfolderDao().getByGlobalId(sourceGlobalId)?.id
            "note" -> db.noteDao().getByGlobalId(sourceGlobalId)?.subfolderId
            else -> null
        }

    private suspend fun resolveScopeLocalId(scopeType: String, scopeGlobalId: String): Long? =
        when (scopeType) {
            "workshop_project", "note", "subfolder" -> db.subfolderDao().getByGlobalId(scopeGlobalId)?.id
            else -> null
        }
}
