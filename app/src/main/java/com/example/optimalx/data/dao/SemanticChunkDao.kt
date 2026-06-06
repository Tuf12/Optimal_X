package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.example.optimalx.data.model.SemanticChunk

@Dao
interface SemanticChunkDao {

    @Insert
    suspend fun insertAll(chunks: List<SemanticChunk>)

    @Query("DELETE FROM semantic_chunks WHERE objectType = :objectType AND objectId = :objectId")
    suspend fun deleteByObject(objectType: String, objectId: Long)

    @Query("DELETE FROM semantic_chunks WHERE subfolderId = :subfolderId")
    suspend fun deleteBySubfolder(subfolderId: Long)

    @Query("DELETE FROM semantic_chunks WHERE parentFolderId = :parentFolderId")
    suspend fun deleteByParentFolder(parentFolderId: Long)

    @Query("SELECT * FROM semantic_chunks")
    suspend fun getAll(): List<SemanticChunk>

    @Query("DELETE FROM semantic_chunks WHERE id NOT IN (:ids)")
    suspend fun deleteExceptIds(ids: List<Long>)

    @Query("DELETE FROM semantic_chunks")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceForObject(objectType: String, objectId: Long, chunks: List<SemanticChunk>) {
        deleteByObject(objectType, objectId)
        if (chunks.isNotEmpty()) {
            insertAll(chunks)
        }
    }
}
