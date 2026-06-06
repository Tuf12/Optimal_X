package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.optimalx.data.model.PanelState

@Dao
interface PanelStateDao {

    @Query(
        """
        SELECT stateJson FROM panel_state
        WHERE workshopSubfolderId = :workshopSubfolderId AND scopeKey = :scopeKey
        LIMIT 1
        """,
    )
    suspend fun getStateJson(workshopSubfolderId: Long, scopeKey: String): String?

    @Query(
        """
        SELECT id FROM panel_state
        WHERE workshopSubfolderId = :workshopSubfolderId AND scopeKey = :scopeKey
        LIMIT 1
        """,
    )
    suspend fun getId(workshopSubfolderId: Long, scopeKey: String): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(state: PanelState): Long

    @Query(
        """
        UPDATE panel_state
        SET stateJson = :stateJson, updatedAt = :updatedAt
        WHERE workshopSubfolderId = :workshopSubfolderId AND scopeKey = :scopeKey
        """,
    )
    suspend fun updateState(
        workshopSubfolderId: Long,
        scopeKey: String,
        stateJson: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM panel_state WHERE workshopSubfolderId = :workshopSubfolderId")
    suspend fun deleteForWorkshopProject(workshopSubfolderId: Long)

    @Query(
        """
        DELETE FROM panel_state
        WHERE workshopSubfolderId = :workshopSubfolderId AND scopeKey = :scopeKey
        """,
    )
    suspend fun deleteScope(workshopSubfolderId: Long, scopeKey: String)
}
