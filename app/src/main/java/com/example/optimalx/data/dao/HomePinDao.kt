package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.optimalx.data.model.HomePin
import kotlinx.coroutines.flow.Flow

@Dao
interface HomePinDao {

    @Query("SELECT * FROM home_pins ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<HomePin>>

    @Query("SELECT * FROM home_pins ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllOnce(): List<HomePin>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pin: HomePin): Long

    @Query("DELETE FROM home_pins WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM home_pins WHERE pinType = :pinType AND targetId = :targetId")
    suspend fun deleteByTarget(pinType: String, targetId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM home_pins WHERE pinType = :pinType AND targetId = :targetId)")
    suspend fun exists(pinType: String, targetId: Long): Boolean

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM home_pins")
    suspend fun maxSortOrder(): Int

    @Query("DELETE FROM home_pins WHERE pinType = 'parent' AND targetId = :parentFolderId")
    suspend fun deleteForParentFolder(parentFolderId: Long)

    @Query("DELETE FROM home_pins WHERE pinType = 'subfolder' AND targetId = :subfolderId")
    suspend fun deleteForSubfolder(subfolderId: Long)

    @Query("DELETE FROM home_pins WHERE pinType = 'panel' AND targetId = :workshopSubfolderId")
    suspend fun deleteForPanel(workshopSubfolderId: Long)

    @Query(
        """
        DELETE FROM home_pins
        WHERE pinType = 'subfolder'
          AND targetId IN (
              SELECT id FROM subfolders WHERE parentFolderId = :parentFolderId
          )
        """,
    )
    suspend fun deleteSubfolderPinsUnderParent(parentFolderId: Long)
}
