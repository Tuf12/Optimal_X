package com.example.optimalx.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncConflictLogicTest {

    @Test
    fun tombstone_appliesWhenIncomingIsNewer() {
        val outcome = SyncConflictLogic.tombstoneOutcome(localDeletedAt = 100L, incomingDeletedAt = 200L)
        assertEquals(SyncApplyOutcome.APPLIED, outcome)
    }

    @Test
    fun tombstone_skipsWhenLocalIsNewer() {
        val outcome = SyncConflictLogic.tombstoneOutcome(localDeletedAt = 300L, incomingDeletedAt = 200L)
        assertEquals(SyncApplyOutcome.SKIPPED, outcome)
    }

    @Test
    fun compareUpdated_conflictOnSameTimestampDifferentHash() {
        val outcome = SyncConflictLogic.compareUpdated(
            localUpdatedAt = 500L,
            incomingUpdatedAt = 500L,
            localHash = "sha256:abc",
            incomingHash = "sha256:def",
            localFingerprint = "a",
            incomingFingerprint = "b",
        )
        assertEquals(SyncApplyOutcome.CONFLICT, outcome)
    }

    @Test
    fun compareUpdated_appliesWhenIncomingIsNewer() {
        val outcome = SyncConflictLogic.compareUpdated(
            localUpdatedAt = 100L,
            incomingUpdatedAt = 200L,
            localHash = null,
            incomingHash = null,
            localFingerprint = "a",
            incomingFingerprint = "b",
        )
        assertEquals(SyncApplyOutcome.APPLIED, outcome)
    }
}
