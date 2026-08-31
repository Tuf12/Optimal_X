package com.example.optimalx.data.revision

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.PendingChangeItem
import com.example.optimalx.data.model.PendingChangeSet
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CheckpointRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CheckpointRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = CheckpointRepository(
            db.contentCheckpointDao(),
            db.contentPatchDao(),
            db.pendingChangeDao(),
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun baselineIfMissing_insertsOnce() = runBlocking {
        val first = repo.baselineIfMissing(SOURCE_TYPE_WORKSHOP_FILE, sourceId = 1, content = "hello")
        assertEquals(0, first.sequence)
        assertEquals("hello", first.contentBlob)
        assertEquals(CHECKPOINT_AUTHOR_SYSTEM, first.author)

        val second = repo.baselineIfMissing(SOURCE_TYPE_WORKSHOP_FILE, sourceId = 1, content = "different")
        assertEquals(first.id, second.id)
        assertEquals("hello", second.contentBlob)
    }

    @Test
    fun createCheckpoint_advancesSequenceAndStoresPatch() = runBlocking {
        repo.baselineIfMissing(SOURCE_TYPE_WORKSHOP_FILE, sourceId = 1, content = "line one\nline two\n")
        val newer = repo.createCheckpoint(
            sourceType = SOURCE_TYPE_WORKSHOP_FILE,
            sourceId = 1,
            content = "line one\nline TWO\n",
            author = CHECKPOINT_AUTHOR_EIDOS,
            label = "Accepted",
        )
        assertEquals(1, newer.sequence)

        val patches = db.contentPatchDao().listForSource(SOURCE_TYPE_WORKSHOP_FILE, 1)
        assertEquals(1, patches.size)
        assertTrue(patches[0].unifiedDiff.contains("-line two"))
        assertTrue(patches[0].unifiedDiff.contains("+line TWO"))
    }

    @Test
    fun createCheckpoint_skipPatchWhenContentIdentical() = runBlocking {
        repo.baselineIfMissing(SOURCE_TYPE_WORKSHOP_FILE, sourceId = 1, content = "same")
        repo.createCheckpoint(
            sourceType = SOURCE_TYPE_WORKSHOP_FILE,
            sourceId = 1,
            content = "same",
            author = CHECKPOINT_AUTHOR_USER,
        )
        val patches = db.contentPatchDao().listForSource(SOURCE_TYPE_WORKSHOP_FILE, 1)
        assertTrue("no patch should exist for identical content", patches.isEmpty())
    }

    @Test
    fun retention_prunesOldestNonBaselineButKeepsBaseline() = runBlocking {
        repo.baselineIfMissing(SOURCE_TYPE_WORKSHOP_FILE, sourceId = 7, content = "baseline")
        // Add 6 more to push past MAX_CHECKPOINTS_PER_SOURCE (5).
        for (i in 1..6) {
            repo.createCheckpoint(
                sourceType = SOURCE_TYPE_WORKSHOP_FILE,
                sourceId = 7,
                content = "v$i",
                author = CHECKPOINT_AUTHOR_EIDOS,
            )
        }

        val all = repo.listForSource(SOURCE_TYPE_WORKSHOP_FILE, 7)
        assertEquals(CheckpointRepository.MAX_CHECKPOINTS_PER_SOURCE, all.size)

        val sequences = all.map { it.sequence }.sorted()
        assertEquals("baseline must survive pruning", 0, sequences.first())
        assertTrue("latest must survive pruning", sequences.contains(6))
        // Oldest non-baseline checkpoints (v1, v2) must be gone.
        assertFalse(sequences.contains(1))
        assertFalse(sequences.contains(2))
    }

    @Test
    fun retention_dropsPatchesAttachedToPrunedCheckpoints() = runBlocking {
        repo.baselineIfMissing(SOURCE_TYPE_WORKSHOP_FILE, sourceId = 9, content = "v0")
        val v1 = repo.createCheckpoint(SOURCE_TYPE_WORKSHOP_FILE, 9, "v1", CHECKPOINT_AUTHOR_EIDOS)
        val v2 = repo.createCheckpoint(SOURCE_TYPE_WORKSHOP_FILE, 9, "v2", CHECKPOINT_AUTHOR_EIDOS)
        for (i in 3..6) {
            repo.createCheckpoint(SOURCE_TYPE_WORKSHOP_FILE, 9, "v$i", CHECKPOINT_AUTHOR_EIDOS)
        }

        // The v0 → v1 and v1 → v2 patches' targets (v1, v2) should be pruned;
        // the patch rows are explicitly dropped by the repository.
        val patchesForV1 = db.contentPatchDao().getBetween(
            fromId = repo.listForSource(SOURCE_TYPE_WORKSHOP_FILE, 9).first { it.sequence == 0 }.id,
            toId = v1.id,
        )
        assertNull("orphaned patch to pruned v1 should be deleted", patchesForV1)
        val patchesForV2 = db.contentPatchDao().getBetween(v1.id, v2.id)
        assertNull("orphaned patch to pruned v2 should be deleted", patchesForV2)
    }

    @Test
    fun retention_clearsPendingItemBaseCheckpointReferences() = runBlocking {
        repo.baselineIfMissing(SOURCE_TYPE_WORKSHOP_FILE, sourceId = 11, content = "v0")
        val v1 = repo.createCheckpoint(SOURCE_TYPE_WORKSHOP_FILE, 11, "v1", CHECKPOINT_AUTHOR_EIDOS)
        for (i in 2..6) {
            repo.createCheckpoint(SOURCE_TYPE_WORKSHOP_FILE, 11, "v$i", CHECKPOINT_AUTHOR_EIDOS)
        }

        val setId = db.pendingChangeDao().insertSet(
            PendingChangeSet(
                scopeType = SCOPE_WORKSHOP_PROJECT,
                scopeId = 11,
                status = "open",
            ),
        )
        val itemId = db.pendingChangeDao().insertItem(
            PendingChangeItem(
                changeSetId = setId,
                sourceType = SOURCE_TYPE_WORKSHOP_FILE,
                sourceId = 11,
                baseCheckpointId = v1.id,
                proposedContent = "proposal",
                proposedHash = ContentDiff.sha256Hex("proposal"),
                unifiedDiff = "+proposal",
                status = PENDING_ITEM_STATUS_PENDING,
            ),
        )

        assertNull(db.contentCheckpointDao().getById(v1.id))
        assertNull(db.pendingChangeDao().getItem(itemId)!!.baseCheckpointId)
    }

    @Test
    fun getLatest_returnsHighestSequence() = runBlocking {
        repo.baselineIfMissing(SOURCE_TYPE_WORKSHOP_FILE, sourceId = 1, content = "a")
        repo.createCheckpoint(SOURCE_TYPE_WORKSHOP_FILE, 1, "b", CHECKPOINT_AUTHOR_EIDOS)
        repo.createCheckpoint(SOURCE_TYPE_WORKSHOP_FILE, 1, "c", CHECKPOINT_AUTHOR_EIDOS)
        val latest = repo.getLatest(SOURCE_TYPE_WORKSHOP_FILE, 1)
        assertNotNull(latest)
        assertEquals(2, latest!!.sequence)
        assertEquals("c", latest.contentBlob)
    }

    @Test
    fun checkpoint_hashMatchesContentDiff() = runBlocking {
        val cp = repo.baselineIfMissing(SOURCE_TYPE_WORKSHOP_FILE, sourceId = 1, content = "hello world")
        assertEquals(ContentDiff.sha256Hex("hello world"), cp.contentHash)
    }

    @Test
    fun isDirtyVsHead_emptyNoCheckpoint_false() = runBlocking {
        assertFalse(repo.isDirtyVsHead(SOURCE_TYPE_NOTE, sourceId = 42L, workingCopy = ""))
    }

    @Test
    fun commitWorkingCopyIfDirty_createsCheckpointWhenDirty() = runBlocking {
        val committed = repo.commitWorkingCopyIfDirty(
            sourceType = SOURCE_TYPE_NOTE,
            sourceId = 7L,
            workingCopy = "user paste",
            author = CHECKPOINT_AUTHOR_USER,
            label = CHECKPOINT_LABEL_COMMITTED_EDITS,
        )
        assertNotNull(committed)
        assertEquals(CHECKPOINT_LABEL_COMMITTED_EDITS, committed!!.label)
        assertFalse(repo.isDirtyVsHead(SOURCE_TYPE_NOTE, 7L, "user paste"))

        val second = repo.commitWorkingCopyIfDirty(
            sourceType = SOURCE_TYPE_NOTE,
            sourceId = 7L,
            workingCopy = "user paste",
            author = CHECKPOINT_AUTHOR_USER,
            label = CHECKPOINT_LABEL_COMMITTED_EDITS,
        )
        assertNull(second)
    }

    @Test
    fun commitWorkingCopyIfDirty_beforeEidosLabel() = runBlocking {
        repo.commitWorkingCopyIfDirty(
            sourceType = SOURCE_TYPE_NOTE,
            sourceId = 9L,
            workingCopy = "draft",
            author = CHECKPOINT_AUTHOR_USER,
            label = CHECKPOINT_LABEL_BEFORE_EIDOS,
        )
        val latest = repo.getLatest(SOURCE_TYPE_NOTE, 9L)
        assertEquals(CHECKPOINT_LABEL_BEFORE_EIDOS, latest?.label)
    }
}
