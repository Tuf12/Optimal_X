package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.optimalx.data.model.ContentCheckpoint
import kotlinx.coroutines.flow.Flow

@Dao
interface ContentCheckpointDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(checkpoint: ContentCheckpoint): Long

    @Query("SELECT * FROM content_checkpoints WHERE id = :id")
    suspend fun getById(id: Long): ContentCheckpoint?

    @Query(
        """
        SELECT * FROM content_checkpoints
        WHERE sourceType = :sourceType AND sourceId = :sourceId
        ORDER BY sequence DESC
        LIMIT 1
        """
    )
    suspend fun getLatest(sourceType: String, sourceId: Long): ContentCheckpoint?

    @Query(
        """
        SELECT MAX(sequence) FROM content_checkpoints
        WHERE sourceType = :sourceType AND sourceId = :sourceId
        """
    )
    suspend fun getMaxSequence(sourceType: String, sourceId: Long): Int?

    @Query(
        """
        SELECT * FROM content_checkpoints
        WHERE sourceType = :sourceType AND sourceId = :sourceId
        ORDER BY sequence DESC
        """
    )
    fun observeForSource(sourceType: String, sourceId: Long): Flow<List<ContentCheckpoint>>

    @Query(
        """
        SELECT * FROM content_checkpoints
        WHERE sourceType = :sourceType AND sourceId = :sourceId
        ORDER BY sequence DESC
        """
    )
    suspend fun listForSource(sourceType: String, sourceId: Long): List<ContentCheckpoint>

    @Query(
        """
        SELECT COUNT(*) FROM content_checkpoints
        WHERE sourceType = :sourceType AND sourceId = :sourceId
        """
    )
    suspend fun count(sourceType: String, sourceId: Long): Int

    /**
     * Retention helper: returns the oldest non-baseline checkpoints to prune when
     * count exceeds the cap. Baseline (sequence = 0) is excluded so the timeline
     * always has an anchor.
     */
    @Query(
        """
        SELECT * FROM content_checkpoints
        WHERE sourceType = :sourceType AND sourceId = :sourceId
          AND sequence > 0
        ORDER BY sequence ASC
        LIMIT :limit
        """
    )
    suspend fun listPruneCandidates(
        sourceType: String,
        sourceId: Long,
        limit: Int,
    ): List<ContentCheckpoint>

    @Query("DELETE FROM content_checkpoints WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        DELETE FROM content_checkpoints
        WHERE sourceType = :sourceType AND sourceId = :sourceId
        """
    )
    suspend fun deleteAllForSource(sourceType: String, sourceId: Long)
}
