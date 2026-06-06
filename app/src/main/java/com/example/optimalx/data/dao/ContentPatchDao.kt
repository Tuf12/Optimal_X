package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.optimalx.data.model.ContentPatch

@Dao
interface ContentPatchDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(patch: ContentPatch): Long

    @Query("SELECT * FROM content_patches WHERE id = :id")
    suspend fun getById(id: Long): ContentPatch?

    @Query(
        """
        SELECT * FROM content_patches
        WHERE sourceType = :sourceType AND sourceId = :sourceId
        ORDER BY id ASC
        """
    )
    suspend fun listForSource(sourceType: String, sourceId: Long): List<ContentPatch>

    @Query(
        """
        SELECT * FROM content_patches
        WHERE fromCheckpointId = :fromId AND toCheckpointId = :toId
        LIMIT 1
        """
    )
    suspend fun getBetween(fromId: Long, toId: Long): ContentPatch?

    @Query(
        """
        DELETE FROM content_patches
        WHERE fromCheckpointId = :checkpointId OR toCheckpointId = :checkpointId
        """
    )
    suspend fun deleteByCheckpoint(checkpointId: Long)
}
