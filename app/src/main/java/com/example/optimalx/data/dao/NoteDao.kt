package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.optimalx.data.model.Note
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): Note?

    @Query("SELECT * FROM notes WHERE subfolderId = :subfolderId AND deletedAt IS NULL LIMIT 1")
    fun getBySubfolder(subfolderId: Long): Flow<Note?>

    @Query("SELECT * FROM notes WHERE subfolderId = :subfolderId AND deletedAt IS NULL LIMIT 1")
    suspend fun getBySubfolderOnce(subfolderId: Long): Note?

    @Query("UPDATE notes SET deletedAt = :timestamp WHERE subfolderId = :subfolderId AND deletedAt IS NULL")
    suspend fun softDeleteBySubfolder(subfolderId: Long, timestamp: Long = System.currentTimeMillis())

    // Cascade soft-delete all notes for subfolders under a parent
    @Query("""
        UPDATE notes SET deletedAt = :timestamp
        WHERE subfolderId IN (SELECT id FROM subfolders WHERE parentFolderId = :parentId)
        AND deletedAt IS NULL
    """)
    suspend fun softDeleteByParentFolder(parentId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET deletedAt = NULL WHERE subfolderId = :subfolderId")
    suspend fun restoreBySubfolder(subfolderId: Long)

    // Full-text search across note content
    @Query("SELECT * FROM notes WHERE content LIKE '%' || :query || '%' AND deletedAt IS NULL")
    suspend fun searchContent(query: String): List<Note>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL")
    suspend fun getAllActiveOnce(): List<Note>
}
