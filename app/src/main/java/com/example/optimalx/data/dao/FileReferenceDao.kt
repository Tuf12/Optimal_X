package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.optimalx.data.imagestudio.FileReferenceWithFolderLabels
import com.example.optimalx.data.model.FileReference
import kotlinx.coroutines.flow.Flow

@Dao
interface FileReferenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fileReference: FileReference): Long

    @Update
    suspend fun update(fileReference: FileReference)

    @Query("DELETE FROM file_references WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM file_references WHERE id = :id")
    suspend fun getById(id: Long): FileReference?

    @Query(
        """
        SELECT * FROM file_references
        WHERE subfolderId = :subfolderId AND deletedAt IS NULL
        ORDER BY createdAt ASC
        """,
    )
    fun getBySubfolder(subfolderId: Long): Flow<List<FileReference>>

    @Query(
        """
        SELECT * FROM file_references
        WHERE subfolderId = :subfolderId AND deletedAt IS NULL
        ORDER BY createdAt ASC
        """,
    )
    suspend fun getBySubfolderOnce(subfolderId: Long): List<FileReference>

    @Query(
        """
        SELECT * FROM file_references
        WHERE subfolderId = :subfolderId
        ORDER BY createdAt ASC
        """,
    )
    suspend fun getAllBySubfolderOnce(subfolderId: Long): List<FileReference>

    @Query("UPDATE file_references SET deletedAt = :timestamp WHERE id = :id")
    suspend fun softDelete(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE file_references SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query(
        """
        UPDATE file_references SET deletedAt = :timestamp
        WHERE subfolderId = :subfolderId AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteBySubfolder(subfolderId: Long, timestamp: Long)

    @Query("UPDATE file_references SET deletedAt = NULL WHERE subfolderId = :subfolderId")
    suspend fun restoreBySubfolder(subfolderId: Long)

    @Query(
        """
        UPDATE file_references SET deletedAt = :timestamp
        WHERE subfolderId IN (SELECT id FROM subfolders WHERE parentFolderId = :parentId)
          AND deletedAt IS NULL
        """,
    )
    suspend fun softDeleteByParentFolder(parentId: Long, timestamp: Long)

    // Hard delete all file references for a subfolder (called before permanently deleting a subfolder)
    @Query("DELETE FROM file_references WHERE subfolderId = :subfolderId")
    suspend fun deleteBySubfolder(subfolderId: Long)

    // Hard delete all file references for subfolders under a parent
    @Query(
        """
        DELETE FROM file_references
        WHERE subfolderId IN (SELECT id FROM subfolders WHERE parentFolderId = :parentId)
    """,
    )
    suspend fun deleteByParentFolder(parentId: Long)

    @Query(
        """
        SELECT * FROM file_references
        WHERE subfolderId = :subfolderId AND fileName = :fileName AND deletedAt IS NULL
        LIMIT 1
        """,
    )
    suspend fun getBySubfolderAndFileName(subfolderId: Long, fileName: String): FileReference?

    @Query("SELECT * FROM file_references ORDER BY createdAt ASC")
    suspend fun getAllOnce(): List<FileReference>

    @Query("SELECT * FROM file_references WHERE globalId = :globalId LIMIT 1")
    suspend fun getByGlobalId(globalId: String): FileReference?

    @Query(
        """
        SELECT * FROM file_references
        WHERE createdAt > :since OR (deletedAt IS NOT NULL AND deletedAt > :since)
        """,
    )
    suspend fun getChangedSince(since: Long): List<FileReference>

    @Query(
        """
        SELECT fr.*, s.name AS subfolderName, p.name AS parentFolderName
        FROM file_references fr
        INNER JOIN subfolders s ON fr.subfolderId = s.id
        INNER JOIN parent_folders p ON s.parentFolderId = p.id
        WHERE LOWER(fr.fileType) = 'image'
          AND fr.deletedAt IS NULL
          AND s.deletedAt IS NULL
          AND p.deletedAt IS NULL
        ORDER BY fr.createdAt DESC
        """,
    )
    fun observeAllImagesWithFolderLabels(): Flow<List<FileReferenceWithFolderLabels>>

    @Query(
        """
        SELECT fr.*, s.name AS subfolderName, p.name AS parentFolderName
        FROM file_references fr
        INNER JOIN subfolders s ON fr.subfolderId = s.id
        INNER JOIN parent_folders p ON s.parentFolderId = p.id
        WHERE LOWER(fr.fileType) = 'image'
          AND fr.deletedAt IS NULL
          AND s.deletedAt IS NULL
          AND p.deletedAt IS NULL
        ORDER BY fr.createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun listAllImagesWithFolderLabels(limit: Int): List<FileReferenceWithFolderLabels>

    @Query(
        """
        SELECT fr.*, s.name AS subfolderName, p.name AS parentFolderName
        FROM file_references fr
        INNER JOIN subfolders s ON fr.subfolderId = s.id
        INNER JOIN parent_folders p ON s.parentFolderId = p.id
        WHERE LOWER(fr.fileType) = 'image'
          AND fr.deletedAt IS NULL
          AND s.deletedAt IS NULL
          AND p.deletedAt IS NULL
          AND fr.subfolderId = :subfolderId
        ORDER BY fr.createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun listSubfolderImagesWithFolderLabels(
        subfolderId: Long,
        limit: Int,
    ): List<FileReferenceWithFolderLabels>

    @Query(
        """
        SELECT fr.*, s.name AS subfolderName, p.name AS parentFolderName
        FROM file_references fr
        INNER JOIN subfolders s ON fr.subfolderId = s.id
        INNER JOIN parent_folders p ON s.parentFolderId = p.id
        WHERE fr.deletedAt IS NOT NULL
          AND s.deletedAt IS NULL
          AND p.deletedAt IS NULL
        ORDER BY fr.deletedAt DESC
        """,
    )
    fun observeIndividuallyDeletedWithFolderLabels(): Flow<List<FileReferenceWithFolderLabels>>
}
