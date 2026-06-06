package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.optimalx.data.model.TagHintLine
import kotlinx.coroutines.flow.Flow

@Dao
interface TagHintLineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(line: TagHintLine): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertByRef(line: TagHintLine): Long

    @Query("DELETE FROM tag_hint_lines WHERE ref = :ref")
    suspend fun deleteByRef(ref: String): Int

    @Query("SELECT * FROM tag_hint_lines WHERE ref = :ref LIMIT 1")
    suspend fun getByRef(ref: String): TagHintLine?

    @Query("SELECT * FROM tag_hint_lines ORDER BY date DESC, updatedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getAll(limit: Int, offset: Int): List<TagHintLine>

    @Query(
        """
        SELECT * FROM tag_hint_lines
        WHERE ref LIKE '%' || :query || '%'
           OR tag LIKE '%' || :query || '%'
           OR hint LIKE '%' || :query || '%'
           OR objectName LIKE '%' || :query || '%'
           OR parentFolderName LIKE '%' || :query || '%'
           OR subfolderName LIKE '%' || :query || '%'
           OR objectType LIKE '%' || :query || '%'
           OR scopeType LIKE '%' || :query || '%'
           OR rootBranch LIKE '%' || :query || '%'
        ORDER BY date DESC, updatedAt DESC
        """
    )
    suspend fun searchByQuery(query: String): List<TagHintLine>

    @Query("SELECT * FROM tag_hint_lines WHERE tag = :tag ORDER BY date DESC, updatedAt DESC")
    suspend fun queryByTag(tag: String): List<TagHintLine>

    @Query(
        """
        SELECT * FROM tag_hint_lines
        WHERE (:dateFrom IS NULL OR date >= :dateFrom)
          AND (:dateTo IS NULL OR date <= :dateTo)
        ORDER BY date DESC, updatedAt DESC
        """
    )
    suspend fun queryByDateRange(dateFrom: Long?, dateTo: Long?): List<TagHintLine>

    @Query("SELECT * FROM tag_hint_lines ORDER BY date DESC, updatedAt DESC")
    fun observeAll(): Flow<List<TagHintLine>>

    @Query("SELECT ref FROM tag_hint_lines")
    suspend fun getAllRefs(): List<String>
}
