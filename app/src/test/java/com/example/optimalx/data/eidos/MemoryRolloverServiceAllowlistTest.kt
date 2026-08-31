package com.example.optimalx.data.eidos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRolloverServiceAllowlistTest {

    @Test
    fun rollover_allowlists_exclude_index_and_tag_hint_tools() {
        val snapshot = MemoryRolloverService.rolloverPhaseAllowlistSnapshotForTests()
        val allTools = snapshot.values.flatten().toSet()

        assertFalse("read_tag_hints must not be in rollover", "read_tag_hints" in allTools)
        assertFalse("upsert_tag_hint must not be exposed to rollover", "upsert_tag_hint" in allTools)
        assertFalse("remove_tag_hint must not be exposed to rollover", "remove_tag_hint" in allTools)
        assertFalse("notify_user must not be exposed to rollover", "notify_user" in allTools)
    }

    @Test
    fun rollover_retrieval_tools_are_limited_to_search_phases() {
        val snapshot = MemoryRolloverService.rolloverPhaseAllowlistSnapshotForTests()

        assertTrue(
            "search_semantic should be in ROOK_KNIGHT_SEARCH",
            "search_semantic" in snapshot["ROOK_KNIGHT_SEARCH"].orEmpty(),
        )
        assertTrue(
            "search_semantic should be in ROOK_LOGICAL_PASS",
            "search_semantic" in snapshot["ROOK_LOGICAL_PASS"].orEmpty(),
        )
        assertFalse(
            "search_semantic should not be in KING_INIT",
            "search_semantic" in snapshot["KING_INIT"].orEmpty(),
        )
        assertFalse(
            "search_semantic should not be in ROOK_PAWN_JOURNAL_WRITE",
            "search_semantic" in snapshot["ROOK_PAWN_JOURNAL_WRITE"].orEmpty(),
        )
        assertFalse(
            "search_semantic should not be in ROOK_PAWN_LTM_PROMOTION",
            "search_semantic" in snapshot["ROOK_PAWN_LTM_PROMOTION"].orEmpty(),
        )
    }
}
