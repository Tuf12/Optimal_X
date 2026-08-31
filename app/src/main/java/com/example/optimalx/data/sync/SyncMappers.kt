package com.example.optimalx.data.sync

import com.example.optimalx.data.model.ContentCheckpoint
import com.example.optimalx.data.model.ContentPatch
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.model.CustomPanelAssignment
import com.example.optimalx.data.model.ChatMessage
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.PanelState
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.PendingChangeItem
import com.example.optimalx.data.model.PendingChangeSet
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.preferences.DumpEditState

internal object SyncMappers {

    fun parentFolder(row: ParentFolder) = SyncParentFolderRow(
        globalId = row.globalId,
        name = row.name,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        sortOrder = row.sortOrder,
        deletedAt = row.deletedAt,
        isSystemFolder = row.isSystemFolder,
        originDeviceId = row.originDeviceId,
    )

    fun subfolder(row: Subfolder, parentFolderGlobalId: String) = SyncSubfolderRow(
        globalId = row.globalId,
        parentFolderGlobalId = parentFolderGlobalId,
        name = row.name,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        sortOrder = row.sortOrder,
        deletedAt = row.deletedAt,
        isSystemSubfolder = row.isSystemSubfolder,
        projectSummary = row.projectSummary,
        projectSummaryUpdatedAt = row.projectSummaryUpdatedAt,
        targetPlatform = row.targetPlatform,
        originDeviceId = row.originDeviceId,
    )

    fun note(row: Note, subfolderGlobalId: String) = SyncNoteRow(
        globalId = row.globalId,
        subfolderGlobalId = subfolderGlobalId,
        content = row.content,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        deletedAt = row.deletedAt,
        aiLocked = row.aiLocked,
        aiBlind = row.aiBlind,
        summary = row.summary,
        summaryUpdatedAt = row.summaryUpdatedAt,
        summaryContentWatermark = row.summaryContentWatermark,
        contentHash = row.contentHash,
        originDeviceId = row.originDeviceId,
    )

    fun fileReference(row: FileReference, subfolderGlobalId: String) = SyncFileReferenceRow(
        globalId = row.globalId,
        subfolderGlobalId = subfolderGlobalId,
        fileName = row.fileName,
        fileType = row.fileType,
        filePath = row.filePath,
        createdAt = row.createdAt,
        originDeviceId = row.originDeviceId,
        metadataJson = row.metadataJson,
        deletedAt = row.deletedAt,
    )

    fun dumpEdit(state: DumpEditState) = SyncDumpEditRow(
        globalId = state.globalId,
        content = state.content,
        aiLocked = state.aiLocked,
        aiBlind = state.aiBlind,
        updatedAt = state.updatedAt,
        contentHash = state.contentHash,
    )

    fun customPanelAssignment(row: CustomPanelAssignment, lookup: SyncIdLookup) =
        SyncCustomPanelAssignmentRow(
            globalId = row.globalId,
            workshopSubfolderGlobalId = lookup.subfolderGlobalId(row.workshopSubfolderId),
            targetSubfolderGlobalId = lookup.subfolderGlobalId(row.targetSubfolderId),
            panelTitle = row.panelTitle,
            createdAt = row.createdAt,
            originDeviceId = row.originDeviceId,
        )

    fun conversation(row: Conversation, lookup: SyncIdLookup) = SyncConversationRow(
        globalId = row.globalId,
        scopeType = row.scopeType,
        parentFolderGlobalId = lookup.optionalParentGlobalId(row.parentFolderId),
        subfolderGlobalId = lookup.optionalSubfolderGlobalId(row.subfolderId),
        webSearchKey = row.webSearchKey,
        title = row.title,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        memoryDepth = row.memoryDepth,
        threadSummary = row.threadSummary,
        threadSummaryThroughMessageGlobalId = lookup.optionalChatMessageGlobalId(row.threadSummaryCoversMessageId),
        originDeviceId = row.originDeviceId,
    )

    fun chatMessage(row: ChatMessage, lookup: SyncIdLookup) = SyncChatMessageRow(
        globalId = row.globalId,
        conversationGlobalId = lookup.conversationGlobalId(row.conversationId),
        role = row.role,
        content = row.content,
        assistantReasoningContent = row.assistantReasoningContent,
        navigationTargetsJson = row.navigationTargetsJson,
        imageAttachmentJson = row.imageAttachmentJson,
        createdAt = row.createdAt,
        originDeviceId = row.originDeviceId,
    )

    fun contentCheckpoint(row: ContentCheckpoint, lookup: SyncIdLookup) = SyncContentCheckpointRow(
        globalId = row.globalId,
        sourceType = row.sourceType,
        sourceGlobalId = lookup.sourceGlobalId(row.sourceType, row.sourceId),
        sequence = row.sequence,
        contentBlob = row.contentBlob,
        contentHash = row.contentHash,
        author = row.author,
        label = row.label,
        conversationGlobalId = lookup.optionalConversationGlobalId(row.conversationId),
        createdAt = row.createdAt,
        originDeviceId = row.originDeviceId,
    )

    fun contentPatch(row: ContentPatch, lookup: SyncIdLookup) = SyncContentPatchRow(
        globalId = row.globalId,
        sourceType = row.sourceType,
        sourceGlobalId = lookup.sourceGlobalId(row.sourceType, row.sourceId),
        fromCheckpointGlobalId = lookup.checkpointGlobalId(row.fromCheckpointId),
        toCheckpointGlobalId = lookup.checkpointGlobalId(row.toCheckpointId),
        unifiedDiff = row.unifiedDiff,
        createdAt = row.createdAt,
        originDeviceId = row.originDeviceId,
    )

    fun pendingChangeSet(row: PendingChangeSet, lookup: SyncIdLookup) = SyncPendingChangeSetRow(
        globalId = row.globalId,
        scopeType = row.scopeType,
        scopeGlobalId = lookup.scopeGlobalId(row.scopeType, row.scopeId),
        conversationGlobalId = lookup.optionalConversationGlobalId(row.conversationId),
        status = row.status,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        originDeviceId = row.originDeviceId,
    )

    fun pendingChangeItem(row: PendingChangeItem, lookup: SyncIdLookup) = SyncPendingChangeItemRow(
        globalId = row.globalId,
        changeSetGlobalId = lookup.changeSetGlobalId(row.changeSetId),
        sourceType = row.sourceType,
        sourceGlobalId = lookup.sourceGlobalId(row.sourceType, row.sourceId),
        baseCheckpointGlobalId = lookup.optionalCheckpointGlobalId(row.baseCheckpointId),
        proposedContent = row.proposedContent,
        proposedHash = row.proposedHash,
        unifiedDiff = row.unifiedDiff,
        status = row.status,
        fileName = row.fileName,
        isNewFile = row.isNewFile,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        originDeviceId = row.originDeviceId,
    )

    fun panelState(row: PanelState, lookup: SyncIdLookup) = SyncPanelStateRow(
        globalId = row.globalId,
        workshopSubfolderGlobalId = lookup.subfolderGlobalId(row.workshopSubfolderId),
        scopeKey = row.scopeKey,
        stateJson = row.stateJson,
        updatedAt = row.updatedAt,
        contentHash = row.contentHash,
        originDeviceId = row.originDeviceId,
    )
}
