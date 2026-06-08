package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
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
    /** Auto-Continue synthetic user row (LLM handoff resend). */
    val isSyntheticHandoff: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
