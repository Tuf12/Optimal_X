package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.optimalx.data.model.CustomPanelAssignment
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomPanelAssignmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(assignment: CustomPanelAssignment): Long

    @Query("DELETE FROM custom_panel_assignments WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM custom_panel_assignments WHERE targetSubfolderId = :subfolderId ORDER BY createdAt ASC")
    fun getByTargetSubfolder(subfolderId: Long): Flow<List<CustomPanelAssignment>>

    @Query("SELECT * FROM custom_panel_assignments WHERE targetSubfolderId = :subfolderId ORDER BY createdAt ASC")
    suspend fun getByTargetSubfolderOnce(subfolderId: Long): List<CustomPanelAssignment>

    @Query("SELECT * FROM custom_panel_assignments WHERE workshopSubfolderId = :workshopSubfolderId ORDER BY createdAt ASC")
    suspend fun getByWorkshopSubfolder(workshopSubfolderId: Long): List<CustomPanelAssignment>

    @Query("DELETE FROM custom_panel_assignments WHERE workshopSubfolderId = :workshopSubfolderId")
    suspend fun deleteByWorkshopSubfolder(workshopSubfolderId: Long)

    @Query("SELECT * FROM custom_panel_assignments WHERE globalId = :globalId LIMIT 1")
    suspend fun getByGlobalId(globalId: String): CustomPanelAssignment?

    @Query("SELECT * FROM custom_panel_assignments WHERE createdAt > :since ORDER BY createdAt")
    suspend fun getChangedSince(since: Long): List<CustomPanelAssignment>

    @Query("SELECT * FROM custom_panel_assignments ORDER BY createdAt")
    suspend fun getAllForSyncLookup(): List<CustomPanelAssignment>
}
