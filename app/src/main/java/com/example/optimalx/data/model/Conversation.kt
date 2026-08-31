package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.optimalx.data.sync.SyncGlobalIds

@Entity(
    tableName = "conversations",
    indices = [
        Index(
            value = ["scopeType", "subfolderId", "webSearchKey"],
            unique = true,
            name = "index_conversations_web_editor_search",
        ),
        Index(
            value = ["scopeType", "webSearchKey"],
            unique = true,
            name = "index_conversations_web_widget_search",
        ),
        Index(value = ["globalId"], unique = true),
    ],
)
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * "general" | "parent" | "subfolder" | "quick_notes_root" | "quick_notes_day"
     * | "web_editor" | "web_widget"
     */
    val scopeType: String,
    /** Set when scopeType == "parent". ID of the user ParentFolder. */
    val parentFolderId: Long? = null,
    /** Set when scopeType == "subfolder" or "web_editor". ID of the user Subfolder. */
    val subfolderId: Long? = null,
    /** Normalized search key for web_editor / web_widget threads; null for main chat. */
    val webSearchKey: String? = null,
    /** Auto-generated: "yyyy-MM-dd — h:mm a" */
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Legacy per-thread memory tier. Unused; kept for Room/sync schema. */
    @Deprecated("Unused — long-thread context is prefetch + verbatim tail")
    val memoryDepth: String? = null,
    /** Unused conversation fold field; kept for Room/sync schema. */
    val threadSummary: String? = null,
    /** Unused; kept for Room/sync schema. */
    val threadSummaryCoversMessageId: Long? = null,
    val threadSummaryUpdatedAt: Long? = null,
    val globalId: String = SyncGlobalIds.newGlobalId(),
    val originDeviceId: String? = null,
)
