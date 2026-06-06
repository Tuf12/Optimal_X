package com.example.optimalx.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.optimalx.data.model.PendingChangeItem
import com.example.optimalx.data.model.PendingChangeSet
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingChangeDao {

    // ── Sets ──────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSet(set: PendingChangeSet): Long

    @Update
    suspend fun updateSet(set: PendingChangeSet)

    @Query("SELECT * FROM pending_change_sets WHERE id = :id")
    suspend fun getSet(id: Long): PendingChangeSet?

    /**
     * The active review set for a scope: `open`, or legacy `partial` rows that still have
     * pending items (older builds flipped to partial after the first per-file decision).
     */
    @Query(
        """
        SELECT * FROM pending_change_sets pcs
        WHERE pcs.scopeType = :scopeType AND pcs.scopeId = :scopeId
          AND (
            pcs.status = 'open'
            OR (
              pcs.status = 'partial'
              AND EXISTS (
                SELECT 1 FROM pending_change_items pci
                WHERE pci.changeSetId = pcs.id AND pci.status = 'pending'
              )
            )
          )
        ORDER BY pcs.createdAt DESC
        LIMIT 1
        """
    )
    suspend fun findOpenSetForScope(scopeType: String, scopeId: Long): PendingChangeSet?

    @Query(
        """
        SELECT * FROM pending_change_sets pcs
        WHERE pcs.scopeType = :scopeType AND pcs.scopeId = :scopeId
          AND (
            pcs.status = 'open'
            OR (
              pcs.status = 'partial'
              AND EXISTS (
                SELECT 1 FROM pending_change_items pci
                WHERE pci.changeSetId = pcs.id AND pci.status = 'pending'
              )
            )
          )
        ORDER BY pcs.createdAt DESC
        LIMIT 1
        """
    )
    fun observeOpenSetForScope(scopeType: String, scopeId: Long): Flow<PendingChangeSet?>

    @Query(
        """
        SELECT * FROM pending_change_sets
        WHERE scopeType = :scopeType AND scopeId = :scopeId
        ORDER BY createdAt DESC
        """
    )
    suspend fun listForScope(scopeType: String, scopeId: Long): List<PendingChangeSet>

    @Query("DELETE FROM pending_change_sets WHERE id = :id")
    suspend fun deleteSet(id: Long)

    // ── Items ─────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: PendingChangeItem): Long

    @Update
    suspend fun updateItem(item: PendingChangeItem)

    @Query("SELECT * FROM pending_change_items WHERE id = :id")
    suspend fun getItem(id: Long): PendingChangeItem?

    @Query(
        """
        SELECT * FROM pending_change_items
        WHERE changeSetId = :setId
        ORDER BY createdAt ASC
        """
    )
    suspend fun listItems(setId: Long): List<PendingChangeItem>

    @Query(
        """
        SELECT * FROM pending_change_items
        WHERE changeSetId = :setId
        ORDER BY createdAt ASC
        """
    )
    fun observeItems(setId: Long): Flow<List<PendingChangeItem>>

    /**
     * Find an existing pending item in [setId] for the same target so a fresh proposal
     * supersedes the previous one instead of stacking duplicates.
     */
    @Query(
        """
        SELECT * FROM pending_change_items
        WHERE changeSetId = :setId
          AND sourceType = :sourceType AND sourceId = :sourceId
          AND status = 'pending'
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun findOpenItemForTarget(
        setId: Long,
        sourceType: String,
        sourceId: Long,
    ): PendingChangeItem?

    @Query(
        """
        SELECT COUNT(*) FROM pending_change_items
        WHERE changeSetId = :setId AND status = 'pending'
        """
    )
    suspend fun countPending(setId: Long): Int

    @Query("DELETE FROM pending_change_items WHERE id = :id")
    suspend fun deleteItem(id: Long)
}
