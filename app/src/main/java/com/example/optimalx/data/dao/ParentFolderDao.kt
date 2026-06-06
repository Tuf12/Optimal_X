package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.optimalx.data.model.ParentFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface ParentFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: ParentFolder): Long

    @Update
    suspend fun update(folder: ParentFolder)

    @Query("DELETE FROM parent_folders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM parent_folders WHERE id = :id")
    suspend fun getById(id: Long): ParentFolder?

    // Active (non-system) folders for the main UI
    @Query("SELECT * FROM parent_folders WHERE deletedAt IS NULL AND isSystemFolder = 0 ORDER BY sortOrder ASC, createdAt ASC")
    fun getActiveUserFolders(): Flow<List<ParentFolder>>

    /** @deprecated Legacy list including system parents in the grid. Use [getActiveUserFolders]. */
    @Query(
        """
        SELECT * FROM parent_folders WHERE deletedAt IS NULL AND (
            isSystemFolder = 0 OR name IN ('Eidos Chats', 'Quick Notes', 'Eidos Reasoning', 'Panel Workshop')
        ) ORDER BY sortOrder ASC, createdAt ASC
        """,
    )
    fun getActiveParentFoldersWithChats(): Flow<List<ParentFolder>>

    // All active folders including system folders (for DB seed checks)
    @Query("SELECT * FROM parent_folders WHERE deletedAt IS NULL ORDER BY sortOrder ASC, createdAt ASC")
    fun getAllActive(): Flow<List<ParentFolder>>

    // Soft-deleted folders for trash screen
    @Query("SELECT * FROM parent_folders WHERE deletedAt IS NOT NULL AND isSystemFolder = 0 ORDER BY deletedAt DESC")
    fun getDeleted(): Flow<List<ParentFolder>>

    @Query("SELECT * FROM parent_folders WHERE name = :name AND isSystemFolder = 1 LIMIT 1")
    suspend fun getSystemFolderByName(name: String): ParentFolder?

    @Query("SELECT * FROM parent_folders WHERE deletedAt IS NULL AND isSystemFolder = 0 AND name LIKE '%' || :query || '%' ORDER BY name ASC")
    suspend fun searchActive(query: String): List<ParentFolder>

    // Cascade soft-delete: sets deletedAt on this folder and all its subfolders (subfolders cascade via their own DAO calls)
    @Query("UPDATE parent_folders SET deletedAt = :timestamp WHERE id = :id")
    suspend fun softDelete(id: Long, timestamp: Long = System.currentTimeMillis())

    // Restore: clear deletedAt
    @Query("UPDATE parent_folders SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("SELECT * FROM parent_folders WHERE deletedAt IS NULL")
    suspend fun getAllActiveOnce(): List<ParentFolder>
}
