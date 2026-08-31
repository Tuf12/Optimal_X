package com.example.optimalx.data.sync

import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder

/**
 * Stable field fingerprints for folder rows at equal [updatedAt].
 * Must use the same formula for local entities and incoming DTOs.
 */
object SyncFingerprints {

    fun parentFolder(row: ParentFolder): String =
        parentFolder(row.name, row.sortOrder, row.deletedAt, row.isSystemFolder)

    fun parentFolder(row: SyncParentFolderRow): String =
        parentFolder(row.name, row.sortOrder, row.deletedAt, row.isSystemFolder)

    fun subfolder(row: Subfolder): String =
        subfolder(row.name, row.sortOrder, row.deletedAt, row.targetPlatform)

    fun subfolder(row: SyncSubfolderRow): String =
        subfolder(row.name, row.sortOrder, row.deletedAt, row.targetPlatform)

    fun conversation(row: com.example.optimalx.data.model.Conversation): String =
        conversation(
            row.scopeType,
            row.parentFolderId,
            row.subfolderId,
            row.webSearchKey,
            row.title,
            row.memoryDepth,
            row.threadSummary,
            row.threadSummaryCoversMessageId,
        )

    fun conversation(row: SyncConversationRow): String =
        conversation(
            row.scopeType,
            row.parentFolderGlobalId,
            row.subfolderGlobalId,
            row.webSearchKey,
            row.title,
            row.memoryDepth,
            row.threadSummary,
            row.threadSummaryThroughMessageGlobalId,
        )

    fun pendingChangeSet(row: com.example.optimalx.data.model.PendingChangeSet): String =
        pendingChangeSet(row.scopeType, row.scopeId, row.conversationId, row.status)

    fun pendingChangeSet(row: SyncPendingChangeSetRow): String =
        pendingChangeSet(row.scopeType, row.scopeGlobalId, row.conversationGlobalId, row.status)

    fun pendingChangeItem(row: com.example.optimalx.data.model.PendingChangeItem): String =
        pendingChangeItem(
            row.changeSetId,
            row.sourceType,
            row.sourceId,
            row.baseCheckpointId,
            row.proposedHash,
            row.status,
            row.fileName,
            row.isNewFile,
        )

    fun pendingChangeItem(row: SyncPendingChangeItemRow): String =
        pendingChangeItem(
            row.changeSetGlobalId,
            row.sourceType,
            row.sourceGlobalId,
            row.baseCheckpointGlobalId,
            row.proposedHash,
            row.status,
            row.fileName,
            row.isNewFile,
        )

    fun customPanelAssignment(row: com.example.optimalx.data.model.CustomPanelAssignment): String =
        "${row.workshopSubfolderId}|${row.targetSubfolderId}|${row.panelTitle}"

    fun customPanelAssignment(row: SyncCustomPanelAssignmentRow): String =
        "${row.workshopSubfolderGlobalId}|${row.targetSubfolderGlobalId}|${row.panelTitle}"

    fun noteContentHash(storedHash: String, content: String): String =
        storedHash.ifBlank { SyncContentHash.noteContentHash(content) }

    fun panelStateHash(storedHash: String, stateJson: String): String =
        storedHash.ifBlank { SyncContentHash.panelStateContentHash(stateJson) }

    private fun parentFolder(
        name: String,
        sortOrder: Int,
        deletedAt: Long?,
        isSystemFolder: Boolean,
    ): String = "$name|$sortOrder|$deletedAt|$isSystemFolder"

    private fun subfolder(
        name: String,
        sortOrder: Int,
        deletedAt: Long?,
        targetPlatform: String,
    ): String {
        val platform = targetPlatform.ifBlank { "mobile" }
        return "$name|$sortOrder|$deletedAt|$platform"
    }

    private fun conversation(
        scopeType: String,
        parentKey: Any?,
        subfolderKey: Any?,
        webSearchKey: String?,
        title: String,
        memoryDepth: String?,
        threadSummary: String?,
        threadSummaryMessageKey: Any?,
    ): String = listOf(
        scopeType,
        parentKey,
        subfolderKey,
        webSearchKey,
        title,
        memoryDepth,
        threadSummary,
        threadSummaryMessageKey,
    ).joinToString("|")

    private fun pendingChangeSet(
        scopeType: String,
        scopeKey: Any,
        conversationKey: Any?,
        status: String,
    ): String = "$scopeType|$scopeKey|$conversationKey|$status"

    private fun pendingChangeItem(
        changeSetKey: Any,
        sourceType: String,
        sourceKey: Any,
        baseCheckpointKey: Any?,
        proposedHash: String,
        status: String,
        fileName: String?,
        isNewFile: Boolean,
    ): String = listOf(
        changeSetKey,
        sourceType,
        sourceKey,
        baseCheckpointKey,
        proposedHash,
        status,
        fileName,
        isNewFile,
    ).joinToString("|")
}
