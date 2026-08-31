package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.optimalx.data.model.Subfolder
import kotlinx.coroutines.flow.Flow

@Dao
interface SubfolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(subfolder: Subfolder): Long

    @Update
    suspend fun update(subfolder: Subfolder)

    @Query("DELETE FROM subfolders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM subfolders WHERE id = :id")
    suspend fun getById(id: Long): Subfolder?

    @Query(
        """
        SELECT * FROM subfolders
        WHERE parentFolderId = :parentId AND name = :name AND deletedAt IS NULL
        LIMIT 1
        """,
    )
    suspend fun getActiveByParentAndName(parentId: Long, name: String): Subfolder?

    // Active user-visible subfolders for a parent folder (system subfolders are internal-only).
    @Query(
        """
        SELECT * FROM subfolders
        WHERE parentFolderId = :parentId
          AND deletedAt IS NULL
          AND isSystemSubfolder = 0
        ORDER BY sortOrder ASC, createdAt ASC
        """
    )
    fun getActiveByParent(parentId: Long): Flow<List<Subfolder>>

    // All active subfolders including system ones (used internally)
    @Query("SELECT * FROM subfolders WHERE parentFolderId = :parentId AND deletedAt IS NULL ORDER BY sortOrder ASC, createdAt ASC")
    fun getAllActiveByParent(parentId: Long): Flow<List<Subfolder>>

    // Soft-deleted subfolders for a parent (for trash screen)
    @Query("SELECT * FROM subfolders WHERE parentFolderId = :parentId AND deletedAt IS NOT NULL AND isSystemSubfolder = 0 ORDER BY deletedAt DESC")
    fun getDeletedByParent(parentId: Long): Flow<List<Subfolder>>

    // All soft-deleted user subfolders across the app (for global trash)
    @Query("SELECT * FROM subfolders WHERE deletedAt IS NOT NULL AND isSystemSubfolder = 0 ORDER BY deletedAt DESC")
    fun getAllDeleted(): Flow<List<Subfolder>>

    @Query("UPDATE subfolders SET deletedAt = :timestamp WHERE id = :id")
    suspend fun softDelete(id: Long, timestamp: Long = System.currentTimeMillis())

    // Cascade soft-delete all subfolders under a parent
    @Query("UPDATE subfolders SET deletedAt = :timestamp WHERE parentFolderId = :parentId AND deletedAt IS NULL")
    suspend fun softDeleteByParent(parentId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE subfolders SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    // Get the system chat subfolder for a given parent (explicitly by name)
    @Query("SELECT * FROM subfolders WHERE parentFolderId = :parentId AND isSystemSubfolder = 1 AND name = 'Chats' LIMIT 1")
    suspend fun getSystemChatSubfolder(parentId: Long): Subfolder?

    @Query("SELECT * FROM subfolders WHERE deletedAt IS NULL AND isSystemSubfolder = 0 AND name LIKE '%' || :query || '%' ORDER BY name ASC")
    suspend fun searchActive(query: String): List<Subfolder>

    // All subfolders under a parent regardless of deletedAt (for permanent delete cascade)
    @Query("SELECT * FROM subfolders WHERE parentFolderId = :parentId")
    suspend fun getAllByParentOnce(parentId: Long): List<Subfolder>

    // Last N conversation subfolders across the system: Eidos Chats root + scoped __chat_ subfolders
    @Query("""
        SELECT s.* FROM subfolders s
        LEFT JOIN notes n ON n.subfolderId = s.id AND n.deletedAt IS NULL
        INNER JOIN parent_folders p ON s.parentFolderId = p.id
        WHERE s.deletedAt IS NULL AND s.isSystemSubfolder = 1
          AND (p.name = :chatsFolderName OR s.name LIKE '__chat_%')
        ORDER BY COALESCE(n.updatedAt, s.createdAt) DESC
        LIMIT :limit
    """)
    suspend fun getRecentConversations(
        chatsFolderName: String = "Eidos Chats",
        limit: Int = 10,
    ): List<Subfolder>

    @Query("SELECT * FROM subfolders WHERE deletedAt IS NULL")
    suspend fun getAllActiveOnce(): List<Subfolder>

    @Query("SELECT * FROM subfolders")
    suspend fun getAllForSyncLookup(): List<Subfolder>

    @Query("SELECT * FROM subfolders WHERE globalId = :globalId LIMIT 1")
    suspend fun getByGlobalId(globalId: String): Subfolder?

    @Query(
        """
        SELECT * FROM subfolders
        WHERE updatedAt > :since OR (deletedAt IS NOT NULL AND deletedAt > :since)
        """,
    )
    suspend fun getChangedSince(since: Long): List<Subfolder>
}
