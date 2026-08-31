package com.example.optimalx.data.revision

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PendingChangeServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var workshopRoot: File
    private lateinit var checkpointRepo: CheckpointRepository
    private lateinit var applier: DirectWriteApplier
    private lateinit var service: PendingChangeService

    private var subfolderId: Long = 0

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        workshopRoot = tempFolder.newFolder("workshop")
        checkpointRepo = CheckpointRepository(
            db.contentCheckpointDao(),
            db.contentPatchDao(),
            db.pendingChangeDao(),
        )
        applier = DirectWriteApplier(
            fileReferenceDao = db.fileReferenceDao(),
            checkpointRepository = checkpointRepo,
            indexer = WorkshopFileIndexer.NoOp,
            noteDao = db.noteDao(),
            noteIndexer = NoteIndexer.NoOp,
            workshopFilePath = { sid, fileName ->
                File(workshopRoot, "$sid/$fileName")
            },
        )
        service = PendingChangeService(
            pendingDao = db.pendingChangeDao(),
            fileReferenceDao = db.fileReferenceDao(),
            noteDao = db.noteDao(),
            checkpointRepository = checkpointRepo,
            directWriteApplier = applier,
        )

        val parentId = db.parentFolderDao().insert(
            ParentFolder(name = "Panel Workshop", isSystemFolder = true),
        )
        subfolderId = db.subfolderDao().insert(
            Subfolder(parentFolderId = parentId, name = "Bid template"),
        )
        db.noteDao().insert(Note(subfolderId = subfolderId, content = "# Seed\n"))
    }

    @After
    fun teardown() {
        db.close()
    }

    // ── Propose ──────────────────────────────────────────────────────────────

    @Test
    fun proposeWorkshopFile_queuesPendingItem() = runBlocking {
        val ref = seedWorkshopFile("index.html", "<h1>old</h1>\n")
        val result = service.proposeWorkshopFile(
            fileReferenceId = ref.id,
            conversationId = 42,
            proposedContent = "<h1>new</h1>\n",
        )
        assertTrue(result is ProposeResult.Queued)
        val queued = result as ProposeResult.Queued

        val item = db.pendingChangeDao().getItem(queued.itemId)
        assertNotNull(item)
        assertEquals(PENDING_ITEM_STATUS_PENDING, item!!.status)
        assertEquals("index.html", item.fileName)
        assertEquals(SOURCE_TYPE_WORKSHOP_FILE, item.sourceType)
        assertEquals(ref.id, item.sourceId)
        assertNotNull(item.baseCheckpointId)
        assertTrue(item.unifiedDiff.contains("-<h1>old</h1>"))
        assertTrue(item.unifiedDiff.contains("+<h1>new</h1>"))

        // Disk untouched.
        assertEquals("<h1>old</h1>\n", File(ref.filePath).readText())
    }

    @Test
    fun proposeWorkshopFile_noOpForUnchangedContent() = runBlocking {
        val ref = seedWorkshopFile("index.html", "same\n")
        val result = service.proposeWorkshopFile(
            fileReferenceId = ref.id,
            conversationId = null,
            proposedContent = "same\n",
        )
        assertTrue("expected NoChange but got $result", result is ProposeResult.NoChange)
        assertNull(service.openSetFor(SCOPE_WORKSHOP_PROJECT, subfolderId))
    }

    @Test
    fun proposeWorkshopFile_secondProposalSupersedesFirst() = runBlocking {
        val ref = seedWorkshopFile("script.js", "v0\n")
        val first = service.proposeWorkshopFile(ref.id, null, "v1\n") as ProposeResult.Queued
        val second = service.proposeWorkshopFile(ref.id, null, "v2\n") as ProposeResult.Queued
        assertEquals(first.setId, second.setId)

        val items = db.pendingChangeDao().listItems(first.setId)
        assertEquals(1, items.size)
        assertEquals("v2\n", items[0].proposedContent)
        assertEquals(second.itemId, items[0].id)
    }

    @Test
    fun proposeWorkshopCreate_queuesNewFileItem() = runBlocking {
        val result = service.proposeWorkshopCreate(
            subfolderId = subfolderId,
            conversationId = 7,
            fileName = "style.css",
            content = "body { color: red; }\n",
        )
        assertTrue(result is ProposeResult.Queued)
        val item = db.pendingChangeDao().getItem((result as ProposeResult.Queued).itemId)!!
        assertTrue(item.isNewFile)
        assertEquals(SOURCE_TYPE_WORKSHOP_NEW_FILE, item.sourceType)
        assertEquals(subfolderId, item.sourceId)
        assertEquals("style.css", item.fileName)
        assertNull(item.baseCheckpointId)
        assertTrue(item.unifiedDiff.contains("+body { color: red; }"))
    }

    @Test
    fun proposeWorkshopCreate_rejectsDuplicateExistingFile() = runBlocking {
        seedWorkshopFile("script.js", "// existing\n")
        val result = service.proposeWorkshopCreate(
            subfolderId = subfolderId,
            conversationId = null,
            fileName = "script.js",
            content = "// new\n",
        )
        assertTrue("expected Failed for existing file but got $result", result is ProposeResult.Failed)
    }

    // ── Accept ───────────────────────────────────────────────────────────────

    @Test
    fun accept_writesFileAndCreatesCheckpoint() = runBlocking {
        val ref = seedWorkshopFile("script.js", "v0\n")
        val queued = service.proposeWorkshopFile(ref.id, null, "v1\n") as ProposeResult.Queued

        val accepted = service.accept(queued.itemId)
        assertTrue("expected Applied but got $accepted", accepted is AcceptResult.Applied)

        assertEquals("v1\n", File(ref.filePath).readText())

        val checkpoints = checkpointRepo.listForSource(SOURCE_TYPE_WORKSHOP_FILE, ref.id)
        assertEquals(2, checkpoints.size)  // baseline + accepted
        val latest = checkpoints.first()
        assertEquals("v1\n", latest.contentBlob)
        assertEquals(CHECKPOINT_AUTHOR_EIDOS, latest.author)

        val item = db.pendingChangeDao().getItem(queued.itemId)!!
        assertEquals(PENDING_ITEM_STATUS_ACCEPTED, item.status)

        val set = db.pendingChangeDao().getSet(queued.setId)!!
        assertEquals(PENDING_SET_STATUS_ACCEPTED, set.status)
    }

    @Test
    fun accept_afterStaleCheckpointBaseline_stillAppliesWhenDiskMatchesProposal() = runBlocking {
        val ref = seedWorkshopFile("style.css", "v0\n")
        checkpointRepo.createCheckpoint(
            sourceType = SOURCE_TYPE_WORKSHOP_FILE,
            sourceId = ref.id,
            content = "stale-build-snapshot\n",
            author = CHECKPOINT_AUTHOR_SYSTEM,
            label = "Design build",
        )
        File(ref.filePath).writeText("v0\n")

        val queued = service.proposeWorkshopFile(ref.id, null, "v1\n") as ProposeResult.Queued
        val result = service.accept(queued.itemId)
        assertTrue("expected Applied but got $result", result is AcceptResult.Applied)
        assertEquals("v1\n", File(ref.filePath).readText())
    }

    @Test
    fun accept_concurrentChange_returnsConcurrentChange() = runBlocking {
        val ref = seedWorkshopFile("script.js", "v0\n")
        val queued = service.proposeWorkshopFile(ref.id, null, "v1\n") as ProposeResult.Queued

        // User edits the file on disk after Eidos proposed.
        File(ref.filePath).writeText("user edited\n")

        val result = service.accept(queued.itemId)
        assertTrue("expected ConcurrentChange but got $result", result is AcceptResult.ConcurrentChange)
        val concurrent = result as AcceptResult.ConcurrentChange
        assertNotEquals(concurrent.expectedHash, concurrent.actualHash)

        // Disk should still hold the user's edit, not the proposed content.
        assertEquals("user edited\n", File(ref.filePath).readText())

        // Item should remain pending so the UI can prompt re-review.
        val item = db.pendingChangeDao().getItem(queued.itemId)!!
        assertEquals(PENDING_ITEM_STATUS_PENDING, item.status)
    }

    @Test
    fun dismiss_afterConcurrentChange_clearsQueueAndKeepsDisk() = runBlocking {
        val ref = seedWorkshopFile("script.js", "v0\n")
        val queued = service.proposeWorkshopFile(ref.id, null, "v1\n") as ProposeResult.Queued
        File(ref.filePath).writeText("user edited\n")

        assertTrue(service.accept(queued.itemId) is AcceptResult.ConcurrentChange)

        val dismissed = service.dismiss(queued.itemId)
        assertTrue("expected Ok but got $dismissed", dismissed is RejectResult.Ok)
        assertEquals("user edited\n", File(ref.filePath).readText())

        val item = db.pendingChangeDao().getItem(queued.itemId)!!
        assertEquals(PENDING_ITEM_STATUS_REJECTED, item.status)
        assertEquals(0, db.pendingChangeDao().countPending(queued.setId))
    }

    @Test
    fun acceptCreate_createsFileReferenceAndDiskFile() = runBlocking {
        val proposal = service.proposeWorkshopCreate(
            subfolderId = subfolderId,
            conversationId = null,
            fileName = "bridge.js",
            content = "console.log('hi');\n",
        ) as ProposeResult.Queued
        val result = service.accept(proposal.itemId)
        assertTrue(result is AcceptResult.Applied)
        val applied = result as AcceptResult.Applied

        val ref = db.fileReferenceDao().getById(applied.fileReferenceId)
        assertNotNull(ref)
        assertEquals("bridge.js", ref!!.fileName)
        assertEquals("console.log('hi');\n", File(ref.filePath).readText())

        // Baseline checkpoint exists for the new file.
        val cp = checkpointRepo.getLatest(SOURCE_TYPE_WORKSHOP_FILE, ref.id)
        assertNotNull(cp)
        assertEquals(0, cp!!.sequence)
        assertEquals("console.log('hi');\n", cp.contentBlob)
    }

    // ── Reject ───────────────────────────────────────────────────────────────

    @Test
    fun reject_keepsDiskUntouched() = runBlocking {
        val ref = seedWorkshopFile("script.js", "v0\n")
        val queued = service.proposeWorkshopFile(ref.id, null, "v1\n") as ProposeResult.Queued

        val result = service.reject(queued.itemId)
        assertTrue("expected Ok but got $result", result is RejectResult.Ok)
        assertEquals("v0\n", File(ref.filePath).readText())

        val item = db.pendingChangeDao().getItem(queued.itemId)!!
        assertEquals(PENDING_ITEM_STATUS_REJECTED, item.status)
        val set = db.pendingChangeDao().getSet(queued.setId)!!
        assertEquals(PENDING_SET_STATUS_REJECTED, set.status)
    }

    // ── Set lifecycle ────────────────────────────────────────────────────────

    @Test
    fun acceptOneOfTwo_keepsSetOpen_withRemainingPending() = runBlocking {
        val refA = seedWorkshopFile("a.js", "a0\n")
        val refB = seedWorkshopFile("b.js", "b0\n")
        val queuedA = service.proposeWorkshopFile(refA.id, null, "a1\n") as ProposeResult.Queued
        val queuedB = service.proposeWorkshopFile(refB.id, null, "b1\n") as ProposeResult.Queued
        assertEquals(queuedA.setId, queuedB.setId)

        service.accept(queuedA.itemId)

        val set = db.pendingChangeDao().getSet(queuedA.setId)!!
        assertEquals(PENDING_SET_STATUS_OPEN, set.status)
        assertNotNull(service.openSetFor(SCOPE_WORKSHOP_PROJECT, subfolderId))
        assertEquals(1, db.pendingChangeDao().countPending(queuedA.setId))
    }

    @Test
    fun acceptAll_partialStatus_whenSomeRejected() = runBlocking {
        val refA = seedWorkshopFile("a.js", "a0\n")
        val refB = seedWorkshopFile("b.js", "b0\n")
        val queuedA = service.proposeWorkshopFile(refA.id, null, "a1\n") as ProposeResult.Queued
        val queuedB = service.proposeWorkshopFile(refB.id, null, "b1\n") as ProposeResult.Queued
        assertEquals(queuedA.setId, queuedB.setId)

        service.reject(queuedB.itemId)
        service.accept(queuedA.itemId)

        val set = db.pendingChangeDao().getSet(queuedA.setId)!!
        assertEquals(PENDING_SET_STATUS_PARTIAL, set.status)
    }

    @Test
    fun openSetFor_returnsCurrentOpenSet_afterAcceptIsCleared() = runBlocking {
        val ref = seedWorkshopFile("script.js", "v0\n")
        val queued = service.proposeWorkshopFile(ref.id, null, "v1\n") as ProposeResult.Queued
        assertNotNull(service.openSetFor(SCOPE_WORKSHOP_PROJECT, subfolderId))

        service.accept(queued.itemId)

        // Set is no longer open once everything is accepted.
        assertNull(service.openSetFor(SCOPE_WORKSHOP_PROJECT, subfolderId))
    }

    @Test
    fun effectiveWorkingContent_usesLatestPendingProposal() = runBlocking {
        val ref = seedWorkshopFile("script.js", "v0\n")
        service.proposeWorkshopFile(ref.id, null, "v1\n")
        assertEquals("v1\n", service.effectiveWorkingContentForFile(ref))
        assertEquals("v0\n", File(ref.filePath).readText())
    }

    @Test
    fun accept_doublePropose_supersedes_andFirstItemIsGone() = runBlocking {
        val ref = seedWorkshopFile("script.js", "v0\n")
        val first = service.proposeWorkshopFile(ref.id, null, "v1\n") as ProposeResult.Queued
        val second = service.proposeWorkshopFile(ref.id, null, "v2\n") as ProposeResult.Queued

        // Accept the second (only surviving) item; first should already be deleted.
        assertNull(db.pendingChangeDao().getItem(first.itemId))
        val accepted = service.accept(second.itemId)
        assertTrue(accepted is AcceptResult.Applied)
        assertEquals("v2\n", File(ref.filePath).readText())
    }

    @Test
    fun proposeNote_queuesUntilAccept() = runBlocking {
        val before = db.noteDao().getBySubfolderOnce(subfolderId)!!.content
        val queued = service.proposeNote(
            subfolderId = subfolderId,
            conversationId = null,
            proposedContent = "$before\n\nAppended block.",
        ) as ProposeResult.Queued

        assertEquals(before, db.noteDao().getBySubfolderOnce(subfolderId)!!.content)
        assertEquals(
            "$before\n\nAppended block.",
            service.effectiveWorkingContentForNote(subfolderId),
        )

        val accept = service.accept(queued.itemId)
        assertTrue(accept is AcceptResult.Applied)
        assertEquals(
            "$before\n\nAppended block.",
            db.noteDao().getBySubfolderOnce(subfolderId)!!.content,
        )
        val item = db.pendingChangeDao().getItem(queued.itemId)
        assertEquals(SOURCE_TYPE_NOTE, item?.sourceType)
        val set = db.pendingChangeDao().findOpenSetForScope(SCOPE_SUBFOLDER, subfolderId)
        assertNotNull(set)
    }

    @Test
    fun acceptOnAlreadyAcceptedItem_fails() = runBlocking {
        val ref = seedWorkshopFile("script.js", "v0\n")
        val queued = service.proposeWorkshopFile(ref.id, null, "v1\n") as ProposeResult.Queued
        service.accept(queued.itemId)
        val second = service.accept(queued.itemId)
        assertTrue("expected Failed but got $second", second is AcceptResult.Failed)
        assertFalse((second as AcceptResult.Failed).message.contains("not found"))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun seedWorkshopFile(name: String, initialContent: String): FileReference {
        val dir = File(workshopRoot, subfolderId.toString())
        dir.mkdirs()
        val file = File(dir, name)
        file.writeText(initialContent)
        val ext = name.substringAfterLast('.', "txt")
        val id = db.fileReferenceDao().insert(
            FileReference(
                subfolderId = subfolderId,
                fileName = name,
                fileType = ext,
                filePath = file.absolutePath,
            ),
        )
        return db.fileReferenceDao().getById(id)!!
    }
}
