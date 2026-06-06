package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.optimalx.data.model.FileReference
import kotlinx.coroutines.flow.Flow

@Dao
interface FileReferenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fileReference: FileReference): Long

    @Query("DELETE FROM file_references WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM file_references WHERE id = :id")
    suspend fun getById(id: Long): FileReference?

    @Query("SELECT * FROM file_references WHERE subfolderId = :subfolderId ORDER BY createdAt ASC")
    fun getBySubfolder(subfolderId: Long): Flow<List<FileReference>>

    @Query("SELECT * FROM file_references WHERE subfolderId = :subfolderId ORDER BY createdAt ASC")
    suspend fun getBySubfolderOnce(subfolderId: Long): List<FileReference>

    // Hard delete all file references for a subfolder (called before permanently deleting a subfolder)
    @Query("DELETE FROM file_references WHERE subfolderId = :subfolderId")
    suspend fun deleteBySubfolder(subfolderId: Long)

    // Hard delete all file references for subfolders under a parent
    @Query("""
        DELETE FROM file_references
        WHERE subfolderId IN (SELECT id FROM subfolders WHERE parentFolderId = :parentId)
    """)
    suspend fun deleteByParentFolder(parentId: Long)

    @Query("SELECT * FROM file_references ORDER BY createdAt ASC")
    suspend fun getAllOnce(): List<FileReference>
}
