package com.example.optimalx.data.revision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteHeadDirtyTest {

    @Test
    fun emptyWorkingCopy_noCheckpoint_isNotDirty() {
        assertFalse(
            CheckpointRepository.isWorkingCopyDirtyVsHead(
                workingCopy = "",
                latestCheckpointHash = null,
            ),
        )
    }

    @Test
    fun nonEmptyWorkingCopy_noCheckpoint_isDirty() {
        assertTrue(
            CheckpointRepository.isWorkingCopyDirtyVsHead(
                workingCopy = "hello",
                latestCheckpointHash = null,
            ),
        )
    }

    @Test
    fun workingCopyMatchesHead_isNotDirty() {
        val hash = ContentDiff.sha256Hex("same body")
        assertFalse(
            CheckpointRepository.isWorkingCopyDirtyVsHead(
                workingCopy = "same body",
                latestCheckpointHash = hash,
            ),
        )
    }

    @Test
    fun workingCopyDiffersFromHead_isDirty() {
        val hash = ContentDiff.sha256Hex("old body")
        assertTrue(
            CheckpointRepository.isWorkingCopyDirtyVsHead(
                workingCopy = "new body",
                latestCheckpointHash = hash,
            ),
        )
    }
}
