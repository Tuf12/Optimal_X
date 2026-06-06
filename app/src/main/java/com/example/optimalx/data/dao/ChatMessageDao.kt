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
