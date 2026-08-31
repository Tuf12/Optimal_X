package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.optimalx.data.sync.SyncGlobalIds

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["globalId"], unique = true)],
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user" | "eidos" */
    val role: String,
    val content: String,
    /**
     * Kimi K2.6 provider thinking text (`reasoning_content`) for assistant rows.
     * Persisted for Moonshot preserved-thinking replay; optional collapsible UI in chat.
     */
    val assistantReasoningContent: String? = null,
    /** JSON array of [EidosNavigationTarget] for assistant rows; Tier 2 sync with desktop. */
    val navigationTargetsJson: String? = null,
    /**
     * Chat vision attach metadata: `{ fileName, mimeType, storedName }`.
     * Bytes are device-local; Tier 2 may sync this JSON without the file.
     */
    val imageAttachmentJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val globalId: String = SyncGlobalIds.newGlobalId(),
    val originDeviceId: String? = null,
)
