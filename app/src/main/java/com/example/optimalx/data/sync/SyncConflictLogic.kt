package com.example.optimalx.data.sync

enum class SyncApplyOutcome {
    APPLIED,
    SKIPPED,
    CONFLICT,
}

data class SyncApplyStats(
    val applied: MutableMap<String, Int> = mutableMapOf(),
    val skipped: MutableMap<String, Int> = mutableMapOf(),
    val conflicts: MutableList<SyncConflictDto> = mutableListOf(),
) {
    fun record(table: String, outcome: SyncApplyOutcome, globalId: String, reason: String = "") {
        when (outcome) {
            SyncApplyOutcome.APPLIED -> applied[table] = (applied[table] ?: 0) + 1
            SyncApplyOutcome.SKIPPED -> skipped[table] = (skipped[table] ?: 0) + 1
            SyncApplyOutcome.CONFLICT -> conflicts += SyncConflictDto(table = table, globalId = globalId, reason = reason)
        }
    }
}

object SyncConflictLogic {

    fun normalizeHash(hash: String): String =
        hash.removePrefix("sha256:").trim().lowercase()

    fun hashesEqual(a: String, b: String): Boolean =
        normalizeHash(a) == normalizeHash(b)

    fun tombstoneOutcome(
        localDeletedAt: Long?,
        incomingDeletedAt: Long?,
    ): SyncApplyOutcome {
        if (incomingDeletedAt == null) return SyncApplyOutcome.SKIPPED
        return if (localDeletedAt == null || incomingDeletedAt >= localDeletedAt) {
            SyncApplyOutcome.APPLIED
        } else {
            SyncApplyOutcome.SKIPPED
        }
    }

    fun compareUpdated(
        localUpdatedAt: Long,
        incomingUpdatedAt: Long,
        localHash: String?,
        incomingHash: String?,
        localFingerprint: String,
        incomingFingerprint: String,
    ): SyncApplyOutcome {
        if (incomingUpdatedAt > localUpdatedAt) return SyncApplyOutcome.APPLIED
        if (incomingUpdatedAt < localUpdatedAt) return SyncApplyOutcome.SKIPPED
        if (localHash != null && incomingHash != null) {
            if (!hashesEqual(localHash, incomingHash)) {
                return SyncApplyOutcome.CONFLICT
            }
        } else if (localFingerprint != incomingFingerprint) {
            return SyncApplyOutcome.CONFLICT
        }
        return SyncApplyOutcome.SKIPPED
    }
}
