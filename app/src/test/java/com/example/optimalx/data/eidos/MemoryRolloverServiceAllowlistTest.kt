package com.example.optimalx.data.eidos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRolloverServiceAllowlistTest {

    @Test
    fun rollover_allowlists_include_read_tag_hints_and_exclude_tag_hint_write_tools() {
        val snapshot = MemoryRolloverService.rolloverPhaseAllowlistSnapshotForTests()
        val allTools = snapshot.values.flatten().toSet()

        assertTrue("read_tag_hints should be available in rollover retrieval phases", "read_tag_hints" in allTools)
        assertFalse("upsert_tag_hint must not be exposed to rollover", "upsert_tag_hint" in allTools)
        assertFalse("remove_tag_hint must not be exposed to rollover", "remove_tag_hint" in allTools)
        assertTrue("read-only tag-hint policy should hold", MemoryRolloverService.rolloverTagHintReadOnlyPolicyForTests())
    }

    @Test
    fun rollover_read_tag_hints_is_limited_to_retrieval_phases() {
        val snapshot = MemoryRolloverService.rolloverPhaseAllowlistSnapshotForTests()

        assertTrue("read_tag_hints should be in ROOK_KNIGHT_SEARCH", "read_tag_hints" in snapshot["ROOK_KNIGHT_SEARCH"].orEmpty())
        assertTrue("read_tag_hints should be in ROOK_LOGICAL_PASS", "read_tag_hints" in snapshot["ROOK_LOGICAL_PASS"].orEmpty())
        assertFalse("read_tag_hints should not be in KING_INIT", "read_tag_hints" in snapshot["KING_INIT"].orEmpty())
        assertFalse("read_tag_hints should not be in ROOK_PAWN_JOURNAL_WRITE", "read_tag_hints" in snapshot["ROOK_PAWN_JOURNAL_WRITE"].orEmpty())
        assertFalse("read_tag_hints should not be in ROOK_PAWN_LTM_PROMOTION", "read_tag_hints" in snapshot["ROOK_PAWN_LTM_PROMOTION"].orEmpty())
    }
}

