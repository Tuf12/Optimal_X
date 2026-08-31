package com.example.optimalx.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

@Serializable
data class SyncStatusResponse(
    val deviceId: String = "",
    val serverTime: Long = 0L,
    val dbVersion: Int = 0,
    val lastSyncAt: Long = 0L,
    val lastLocalChangeAt: Long = 0L,
    val pendingConflicts: Int = 0,
    val tableCounts: JsonObject? = null,
)

@Serializable
data class SyncPushPullRequest(
    val deviceId: String,
    val lastSyncAt: Long,
    val tiers: List<Int> = listOf(1),
    val tables: SyncTablesPayload? = null,
)

@Serializable
data class SyncTablesPayload(
    @JsonNames("parentFolders", "parent_folders")
    val parentFolders: List<SyncParentFolderRow> = emptyList(),
    val subfolders: List<SyncSubfolderRow> = emptyList(),
    @JsonNames("notes")
    val notes: List<SyncNoteRow> = emptyList(),
    @JsonNames("fileReferences", "file_references")
    val fileReferences: List<SyncFileReferenceRow> = emptyList(),
    @JsonNames("customPanelAssignments", "custom_panel_assignments")
    val customPanelAssignments: List<SyncCustomPanelAssignmentRow> = emptyList(),
    val conversations: List<SyncConversationRow> = emptyList(),
    @JsonNames("chatMessages", "chat_messages")
    val chatMessages: List<SyncChatMessageRow> = emptyList(),
    @JsonNames("contentCheckpoints", "content_checkpoints")
    val contentCheckpoints: List<SyncContentCheckpointRow> = emptyList(),
    @JsonNames("contentPatches", "content_patches")
    val contentPatches: List<SyncContentPatchRow> = emptyList(),
    @JsonNames("pendingChangeSets", "pending_change_sets")
    val pendingChangeSets: List<SyncPendingChangeSetRow> = emptyList(),
    @JsonNames("pendingChangeItems", "pending_change_items")
    val pendingChangeItems: List<SyncPendingChangeItemRow> = emptyList(),
    @JsonNames("panelState", "panel_state")
    val panelState: List<SyncPanelStateRow> = emptyList(),
    @JsonNames("dumpEdit", "dump_edit")
    val dumpEdit: SyncDumpEditRow? = null,
)

@Serializable
data class SyncApplyResponse(
    val applied: Map<String, Int> = emptyMap(),
    val skipped: Map<String, Int> = emptyMap(),
    val conflicts: List<SyncConflictDto> = emptyList(),
    val serverTime: Long = 0L,
    val deviceId: String = "",
    val tables: SyncTablesPayload? = null,
)

@Serializable
data class SyncConflictDto(
    val table: String,
    val globalId: String,
    val reason: String = "",
)

@Serializable
data class SyncResolveRequest(
    val resolutions: List<SyncResolutionDto>,
)

@Serializable
data class SyncResolutionDto(
    val table: String,
    val globalId: String,
    val choice: String,
    val mergedRow: JsonObject? = null,
)

@Serializable
data class SyncParentFolderRow(
    val globalId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sortOrder: Int = 0,
    val deletedAt: Long? = null,
    val isSystemFolder: Boolean = false,
    val originDeviceId: String? = null,
)

@Serializable
data class SyncSubfolderRow(
    val globalId: String,
    val parentFolderGlobalId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sortOrder: Int = 0,
    val deletedAt: Long? = null,
    val isSystemSubfolder: Boolean = false,
    val projectSummary: String? = null,
    val projectSummaryUpdatedAt: Long? = null,
    val targetPlatform: String = "mobile",
    val originDeviceId: String? = null,
)

@Serializable
data class SyncNoteRow(
    val globalId: String,
    val subfolderGlobalId: String,
    val content: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val aiLocked: Boolean = false,
    val aiBlind: Boolean = false,
    val summary: String? = null,
    val summaryUpdatedAt: Long? = null,
    val summaryContentWatermark: Int? = null,
    val contentHash: String = "",
    val originDeviceId: String? = null,
)

@Serializable
data class SyncFileReferenceRow(
    val globalId: String,
    val subfolderGlobalId: String,
    val fileName: String,
    val fileType: String,
    val filePath: String,
    val createdAt: Long,
    val originDeviceId: String? = null,
    val metadataJson: String? = null,
    val deletedAt: Long? = null,
)

@Serializable
data class SyncDumpEditRow(
    val globalId: String,
    val content: String = "",
    val aiLocked: Boolean = false,
    val aiBlind: Boolean = false,
    val updatedAt: Long,
    val contentHash: String = "",
    val originDeviceId: String? = null,
)

@Serializable
data class SyncCustomPanelAssignmentRow(
    val globalId: String,
    val workshopSubfolderGlobalId: String,
    val targetSubfolderGlobalId: String,
    val panelTitle: String,
    val createdAt: Long,
    val originDeviceId: String? = null,
)

@Serializable
data class SyncConversationRow(
    val globalId: String,
    val scopeType: String,
    val parentFolderGlobalId: String? = null,
    val subfolderGlobalId: String? = null,
    val webSearchKey: String? = null,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val memoryDepth: String? = null,
    val threadSummary: String? = null,
    val threadSummaryThroughMessageGlobalId: String? = null,
    val originDeviceId: String? = null,
)

@Serializable
data class SyncChatMessageRow(
    val globalId: String,
    val conversationGlobalId: String,
    val role: String,
    val content: String = "",
    val assistantReasoningContent: String? = null,
    val navigationTargetsJson: String? = null,
    val imageAttachmentJson: String? = null,
    val createdAt: Long,
    val originDeviceId: String? = null,
)

@Serializable
data class SyncContentCheckpointRow(
    val globalId: String,
    val sourceType: String,
    val sourceGlobalId: String,
    val sequence: Int,
    val contentBlob: String = "",
    val contentHash: String,
    val author: String,
    val label: String? = null,
    val conversationGlobalId: String? = null,
    val createdAt: Long,
    val originDeviceId: String? = null,
)

@Serializable
data class SyncContentPatchRow(
    val globalId: String,
    val sourceType: String,
    val sourceGlobalId: String,
    val fromCheckpointGlobalId: String,
    val toCheckpointGlobalId: String,
    val unifiedDiff: String = "",
    val createdAt: Long,
    val originDeviceId: String? = null,
)

@Serializable
data class SyncPendingChangeSetRow(
    val globalId: String,
    val scopeType: String,
    val scopeGlobalId: String,
    val conversationGlobalId: String? = null,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val originDeviceId: String? = null,
)

@Serializable
data class SyncPendingChangeItemRow(
    val globalId: String,
    val changeSetGlobalId: String,
    val sourceType: String,
    val sourceGlobalId: String,
    val baseCheckpointGlobalId: String? = null,
    val proposedContent: String = "",
    val proposedHash: String,
    val unifiedDiff: String? = null,
    val status: String,
    val fileName: String? = null,
    val isNewFile: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val originDeviceId: String? = null,
)

@Serializable
data class SyncPanelStateRow(
    val globalId: String,
    val workshopSubfolderGlobalId: String,
    val scopeKey: String,
    val stateJson: String = "{}",
    val updatedAt: Long,
    val contentHash: String = "",
    val originDeviceId: String? = null,
)
