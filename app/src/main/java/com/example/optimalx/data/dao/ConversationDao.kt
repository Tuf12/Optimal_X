package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.optimalx.data.model.Conversation

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): Conversation?

    // ── Scoped queries ────────────────────────────────────────────────────────

    @Query("SELECT * FROM conversations WHERE scopeType = 'dump_edit' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentDumpEdit(limit: Int): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'panel_gallery' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentPanelGallery(limit: Int): List<Conversation>

    @Query(
        """
        SELECT * FROM conversations
        WHERE scopeType = 'panel_runner' AND subfolderId = :subfolderId
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentPanelRunner(subfolderId: Long, limit: Int): List<Conversation>

    @Query(
        """
        SELECT * FROM conversations
        WHERE scopeType = 'image_studio' AND subfolderId = :subfolderId
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentImageStudio(subfolderId: Long, limit: Int): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'general' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentGeneral(limit: Int): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'parent' AND parentFolderId = :parentFolderId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentByParentFolder(parentFolderId: Long, limit: Int): List<Conversation>

    @Query(
        """
        SELECT c.* FROM conversations c
        WHERE
            (c.scopeType = 'parent' AND c.parentFolderId = :parentFolderId)
            OR
            (
                c.scopeType = 'subfolder'
                AND c.subfolderId IN (
                    SELECT s.id
                    FROM subfolders s
                    WHERE s.parentFolderId = :parentFolderId
                      AND s.deletedAt IS NULL
                )
            )
        ORDER BY c.updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentByParentIncludingSubfolders(parentFolderId: Long, limit: Int): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'panel_workshop' AND subfolderId = :subfolderId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentPanelWorkshop(subfolderId: Long, limit: Int): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'panel_workshop' AND subfolderId = :subfolderId ORDER BY updatedAt DESC")
    suspend fun getAllPanelWorkshop(subfolderId: Long): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'panel_workshop' AND subfolderId = :subfolderId AND title LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun searchPanelWorkshop(subfolderId: Long, query: String): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'subfolder' AND subfolderId = :subfolderId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentBySubfolder(subfolderId: Long, limit: Int): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'quick_notes_root' AND parentFolderId = :parentFolderId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentQuickNotesRoot(parentFolderId: Long, limit: Int): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'quick_notes_day' AND subfolderId = :subfolderId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentQuickNotesDay(subfolderId: Long, limit: Int): List<Conversation>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentAll(limit: Int): List<Conversation>

    @Query(
        """
        SELECT * FROM conversations
        WHERE scopeType IN ('general', 'parent', 'subfolder', 'quick_notes_root', 'quick_notes_day', 'panel_workshop')
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentMainChat(limit: Int): List<Conversation>

    @Query(
        """
        SELECT * FROM conversations
        WHERE scopeType = 'web_editor'
          AND subfolderId = :subfolderId
          AND webSearchKey = :webSearchKey
        LIMIT 1
        """,
    )
    suspend fun getByWebEditorSearch(subfolderId: Long, webSearchKey: String): Conversation?

    @Query(
        """
        SELECT * FROM conversations
        WHERE scopeType = 'web_widget'
          AND webSearchKey = :webSearchKey
        LIMIT 1
        """,
    )
    suspend fun getByWebWidgetSearch(webSearchKey: String): Conversation?

    @Query(
        """
        DELETE FROM conversations
        WHERE scopeType = 'web_editor'
          AND subfolderId = :subfolderId
          AND webSearchKey = :webSearchKey
        """,
    )
    suspend fun deleteWebEditorSearch(subfolderId: Long, webSearchKey: String)

    @Query(
        """
        DELETE FROM conversations
        WHERE scopeType = 'web_widget'
          AND webSearchKey = :webSearchKey
        """,
    )
    suspend fun deleteWebWidgetSearch(webSearchKey: String)

    @Query(
        """
        SELECT parentFolderId
        FROM conversations
        WHERE scopeType = 'parent' AND parentFolderId IS NOT NULL
        GROUP BY parentFolderId
        ORDER BY MAX(updatedAt) DESC
        """
    )
    suspend fun getParentDirectoryIdsByRecency(): List<Long>

    @Query(
        """
        SELECT subfolderId
        FROM conversations
        WHERE scopeType = 'subfolder' AND subfolderId IS NOT NULL
        GROUP BY subfolderId
        ORDER BY MAX(updatedAt) DESC
        """
    )
    suspend fun getSubfolderDirectoryIdsByRecency(): List<Long>

    // Full lists for ConversationListScreen (no limit)
    @Query("SELECT * FROM conversations WHERE scopeType = 'general' ORDER BY updatedAt DESC")
    suspend fun getAllGeneral(): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'parent' AND parentFolderId = :parentFolderId ORDER BY updatedAt DESC")
    suspend fun getAllByParentFolder(parentFolderId: Long): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'subfolder' AND subfolderId = :subfolderId ORDER BY updatedAt DESC")
    suspend fun getAllBySubfolder(subfolderId: Long): List<Conversation>

    // ── Search ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM conversations WHERE scopeType = 'general' AND title LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun searchGeneral(query: String): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'parent' AND parentFolderId = :parentFolderId AND title LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun searchByParentFolder(parentFolderId: Long, query: String): List<Conversation>

    @Query("SELECT * FROM conversations WHERE scopeType = 'subfolder' AND subfolderId = :subfolderId AND title LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun searchBySubfolder(subfolderId: Long, query: String): List<Conversation>

    // ── Delete ────────────────────────────────────────────────────────────────

    /** Hard delete a single conversation (messages cascade via FK or ChatMessageDao). */
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun getAllForEmbedding(): List<Conversation>

    /** Cascade: delete all conversations when a parent folder is deleted. */
    @Query("DELETE FROM conversations WHERE parentFolderId = :parentFolderId")
    suspend fun deleteAllByParentFolder(parentFolderId: Long)

    /** Cascade: delete all conversations when a subfolder is deleted. */
    @Query("DELETE FROM conversations WHERE subfolderId = :subfolderId")
    suspend fun deleteAllBySubfolder(subfolderId: Long)

    @Query("SELECT * FROM conversations WHERE globalId = :globalId LIMIT 1")
    suspend fun getByGlobalId(globalId: String): Conversation?

    @Query("SELECT * FROM conversations WHERE updatedAt > :since ORDER BY updatedAt")
    suspend fun getChangedSince(since: Long): List<Conversation>

    @Query("SELECT * FROM conversations ORDER BY updatedAt")
    suspend fun getAllForSyncLookup(): List<Conversation>
}
