package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.optimalx.data.model.SemanticVector

@Dao
interface SemanticVectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vector: SemanticVector): Long

    @Query("SELECT * FROM semantic_vectors")
    suspend fun getAll(): List<SemanticVector>

    @Query("DELETE FROM semantic_vectors WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun deleteBySource(sourceType: String, sourceId: Long)

    @Query("DELETE FROM semantic_vectors WHERE sourceType = :sourceType")
    suspend fun deleteByType(sourceType: String)
}
