package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.optimalx.data.model.ChatMessage

@Dao
interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessage): Long

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getAllByConversation(conversationId: Long): List<ChatMessage>

    /**
     * History for API sends — omits [ChatMessage.assistantReasoningContent] so oversized Kimi
     * reasoning rows cannot blow the SQLite CursorWindow when loading long workshop threads.
     */
    @Query(
        """
        SELECT id, conversationId, role,
        SUBSTR(content, 1, :maxContentChars) AS content,
        createdAt, isSyntheticHandoff
        FROM chat_messages
        WHERE conversationId = :conversationId
        ORDER BY createdAt ASC
        """,
    )
    suspend fun getApiHistoryByConversation(
        conversationId: Long,
        maxContentChars: Int,
    ): List<ChatMessage>

    /**
     * Truncates oversized columns without loading full blobs into a [android.database.CursorWindow].
     */
    @Query(
        """
        UPDATE chat_messages SET
            content = CASE
                WHEN length(content) > :maxContent
                    THEN substr(content, 1, max(0, :maxContent - length(:contentSuffix))) || :contentSuffix
                ELSE content
            END,
            assistantReasoningContent = CASE
                WHEN assistantReasoningContent IS NOT NULL
                    AND length(assistantReasoningContent) > :maxReasoning
                    THEN substr(
                        assistantReasoningContent,
                        1,
                        max(0, :maxReasoning - length(:reasoningSuffix))
                    ) || :reasoningSuffix
                ELSE assistantReasoningContent
            END
        WHERE conversationId = :conversationId
            AND (
                length(content) > :maxContent
                OR (
                    assistantReasoningContent IS NOT NULL
                    AND length(assistantReasoningContent) > :maxReasoning
                )
            )
        """,
    )
    suspend fun repairOversizedRowsForConversation(
        conversationId: Long,
        maxContent: Int,
        maxReasoning: Int,
        contentSuffix: String,
        reasoningSuffix: String,
    )

    /** Returns the first message in a conversation (for snippet generation). */
    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC LIMIT 1")
    suspend fun getFirstMessage(conversationId: Long): ChatMessage?

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getById(id: Long): ChatMessage?

    @Query("UPDATE chat_messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    /** Delete all messages in a conversation that were created after the given timestamp. */
    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId AND createdAt > :afterTimestamp")
    suspend fun deleteAfter(conversationId: Long, afterTimestamp: Long)

    /** Cascade delete: remove all messages when their conversation is deleted. */
    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteAllByConversation(conversationId: Long)
}
