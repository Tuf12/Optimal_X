package com.example.optimalx.data.eidos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.eidos.agentbyte.TagHintNotifier
import com.example.optimalx.data.eidos.model.ToolExecutionResult
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.semantic.EmbeddingEngine
import com.example.optimalx.data.semantic.SemanticIndexer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for the two full-content workshop write tools
 * (`workshop_write_file` and `workshop_create_file`) after Phase 3 wired them
 * through [com.example.optimalx.data.revision.WorkshopWriteRouter]:
 *
 * - Build phases auto-accept (disk written, checkpoint created).
 * - Review phases queue pending changes (disk untouched).
 *
 * `workshop_replace_string` has its own focused test
 * ([RoomToolExecutorWorkshopReplaceStringTest]); this test only re-covers what's
 * unique to the full-content tools (creating a new file, no-op writes, duplicate
 * filename rejection).
 */
@RunWith(AndroidJUnit4::class)
class RoomToolExecutorWorkshopWriteTest {

    private lateinit var db: AppDatabase
    private lateinit var executor: RoomToolExecutor
    private lateinit var workshopRoot: File
    private var workshopParentId: Long = 0L
    private var projectSubfolderId: Long = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        executor = RoomToolExecutor(
            context = context,
            db = db,
            semanticIndexer = SemanticIndexer(db, EmbeddingEngine(context)),
            tagHintNotifier = TagHintNotifier.NoOp,
        )
        workshopRoot = File(context.filesDir, "workshop")

        runBlocking {
            workshopParentId = db.parentFolderDao().insert(
                ParentFolder(name = SystemFolderNames.PANEL_WORKSHOP, sortOrder = 0, isSystemFolder = true),
            )
            projectSubfolderId = db.subfolderDao().insert(
                Subfolder(parentFolderId = workshopParentId, name = "Test Panel", sortOrder = 0),
            )
        }
    }

    @After
    fun teardown() {
        db.close()
        WorkshopEidosSession.end()
        workshopRoot.deleteRecursively()
    }

    // ── workshop_write_file ─────────────────────────────────────────────────

    @Test
    fun writeFile_inBuildMode_writesDiskAndCreatesCheckpoint() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val ref = seedWorkshopFile(fileName = "script.js", content = "const v = 1;\n")

        val result = executor.execute(
            "workshop_write_file",
            """{"fileReferenceId":"${ref.id}","content":"const v = 42;\n"}""",
        )

        assertTrue("expected Success, got $result", result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertTrue(success.modifiedSystem)
        assertTrue(success.content.contains("Updated"))
        assertEquals("const v = 42;\n", File(ref.filePath).readText())

        val latest = db.contentCheckpointDao().getLatest("workshop_file", ref.id)
        assertNotNull(latest)
        assertTrue("post-write checkpoint should be > baseline", (latest?.sequence ?: 0) >= 1)
        // Phase 6b: build-mode auto-accept produces a `system` author + phase-labeled checkpoint.
        assertEquals("system", latest?.author)
        assertEquals("Logic build", latest?.label)
    }

    @Test
    fun writeFile_inDesignBuildMode_labelsCheckpointAsDesignBuild() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_DESIGN,
            phase = WorkshopProjectPhase.DESIGN_BUILD,
        )
        val ref = seedWorkshopFile(fileName = "style.css", content = "body { color: black; }\n")

        val result = executor.execute(
            "workshop_write_file",
            """{"fileReferenceId":"${ref.id}","content":"body { color: red; }\n"}""",
        )

        assertTrue("expected Success, got $result", result is ToolExecutionResult.Success)
        val latest = db.contentCheckpointDao().getLatest("workshop_file", ref.id)
        assertNotNull(latest)
        assertEquals("system", latest?.author)
        assertEquals("Design build", latest?.label)
    }

    @Test
    fun writeFile_inReviewMode_queuesProposal_diskUntouched() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.EDIT,
            phase = WorkshopProjectPhase.UPDATE,
        )
        val ref = seedWorkshopFile(fileName = "script.js", content = "const v = 1;\n")
        val before = File(ref.filePath).readText()

        val result = executor.execute(
            "workshop_write_file",
            """{"fileReferenceId":"${ref.id}","content":"const v = 42;\n"}""",
        )

        assertTrue("expected Success, got $result", result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertTrue(
            "review-mode result should mention proposal: ${success.content}",
            success.content.contains("Proposal queued for review"),
        )
        assertEquals(before, File(ref.filePath).readText())

        val openSet = db.pendingChangeDao().findOpenSetForScope("workshop_project", projectSubfolderId)
        assertNotNull(openSet)
        val items = db.pendingChangeDao().listItems(openSet!!.id)
        assertEquals(1, items.size)
        assertFalse("write tool should NOT mark item as new file", items[0].isNewFile)
        assertEquals("script.js", items[0].fileName)
    }

    @Test
    fun writeFile_identicalContent_isNoOp_inBuildMode() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val ref = seedWorkshopFile(fileName = "script.js", content = "const v = 1;\n")

        val result = executor.execute(
            "workshop_write_file",
            """{"fileReferenceId":"${ref.id}","content":"const v = 1;\n"}""",
        )

        assertTrue("expected Success, got $result", result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertFalse("no-op should not be marked as modifying system", success.modifiedSystem)
        assertTrue("message should indicate no change: ${success.content}", success.content.contains("No changes"))
        // Disk unchanged, and no checkpoints created (router skips early).
        assertEquals("const v = 1;\n", File(ref.filePath).readText())
        assertNull(db.contentCheckpointDao().getLatest("workshop_file", ref.id))
    }

    @Test
    fun writeFile_inChatMode_isRejected() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.CHAT,
            phase = WorkshopProjectPhase.UPDATE,
        )
        val ref = seedWorkshopFile(fileName = "script.js", content = "const v = 1;\n")
        val before = File(ref.filePath).readText()

        val result = executor.execute(
            "workshop_write_file",
            """{"fileReferenceId":"${ref.id}","content":"const v = 42;\n"}""",
        )

        assertTrue(result is ToolExecutionResult.Failure)
        assertEquals(before, File(ref.filePath).readText())
        assertNull(db.pendingChangeDao().findOpenSetForScope("workshop_project", projectSubfolderId))
    }

    // ── workshop_create_file ────────────────────────────────────────────────

    @Test
    fun createFile_inBuildMode_writesFileAndCreatesBaseline() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_DESIGN,
            phase = WorkshopProjectPhase.DESIGN_BUILD,
        )

        val result = executor.execute(
            "workshop_create_file",
            """{"subfolderId":"$projectSubfolderId","fileName":"index.html","content":"<html></html>"}""",
        )

        assertTrue("expected Success, got $result", result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertTrue(success.modifiedSystem)
        assertTrue("create message should mention 'Created': ${success.content}", success.content.contains("Created"))

        val refs = db.fileReferenceDao().getBySubfolderOnce(projectSubfolderId)
        assertEquals(1, refs.size)
        val ref = refs[0]
        assertEquals("index.html", ref.fileName)
        assertEquals("<html></html>", File(ref.filePath).readText())

        // Baseline checkpoint should exist for the new file (seq 0). In a build
        // phase the auto-accept attribution (Phase 6b) labels the kickoff with
        // the build mode rather than the generic "Initial create".
        val baseline = db.contentCheckpointDao().getLatest("workshop_file", ref.id)
        assertNotNull(baseline)
        assertEquals(0, baseline?.sequence)
        assertEquals("system", baseline?.author)
        assertEquals("Design build", baseline?.label)
    }

    @Test
    fun createFile_inReviewMode_queuesProposal_noFileReferenceYet() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.EDIT,
            phase = WorkshopProjectPhase.UPDATE,
        )

        val result = executor.execute(
            "workshop_create_file",
            """{"subfolderId":"$projectSubfolderId","fileName":"new.js","content":"const v = 1;\n"}""",
        )

        assertTrue("expected Success, got $result", result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertTrue(
            "review-mode result should mention proposal: ${success.content}",
            success.content.contains("queued for review"),
        )
        // No FileReference and no disk file until Accept.
        assertEquals(0, db.fileReferenceDao().getBySubfolderOnce(projectSubfolderId).size)
        val disk = File(File(workshopRoot, projectSubfolderId.toString()), "new.js")
        assertFalse("disk file should not exist yet: ${disk.absolutePath}", disk.exists())

        val openSet = db.pendingChangeDao().findOpenSetForScope("workshop_project", projectSubfolderId)
        assertNotNull(openSet)
        val items = db.pendingChangeDao().listItems(openSet!!.id)
        assertEquals(1, items.size)
        assertTrue("create tool should mark item as new file", items[0].isNewFile)
        assertEquals("new.js", items[0].fileName)
        assertEquals("workshop_new_file", items[0].sourceType)
    }

    @Test
    fun createFile_duplicateName_returnsFailure_noQueue() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.EDIT,
            phase = WorkshopProjectPhase.UPDATE,
        )
        seedWorkshopFile(fileName = "existing.js", content = "const v = 1;\n")

        val result = executor.execute(
            "workshop_create_file",
            """{"subfolderId":"$projectSubfolderId","fileName":"existing.js","content":"const v = 2;\n"}""",
        )

        assertTrue("expected Failure, got $result", result is ToolExecutionResult.Failure)
        val failure = result as ToolExecutionResult.Failure
        assertTrue(failure.message.contains("already exists"))
        assertNull(db.pendingChangeDao().findOpenSetForScope("workshop_project", projectSubfolderId))
    }

    @Test
    fun readFile_small_returnsJsonEnvelopeWithContent() = runBlocking {
        val ref = seedWorkshopFile(fileName = "FLOW.md", content = "# Flow\n\nStep one.\n")
        val result = executor.execute(
            "workshop_read_file",
            """{"fileReferenceId":"${ref.id}"}""",
        )
        assertTrue(result is ToolExecutionResult.Success)
        val payload = Json.parseToJsonElement((result as ToolExecutionResult.Success).content).jsonObject
        assertTrue(payload["content"]?.jsonPrimitive?.content?.contains("Step one") == true)
        assertEquals("false", payload["truncated"]?.jsonPrimitive?.content)
        assertTrue((payload["totalLines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) >= 2)
    }

    @Test
    fun createFile_invalidFileName_withSlash_returnsFailure() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_DESIGN,
            phase = WorkshopProjectPhase.DESIGN_BUILD,
        )
        val result = executor.execute(
            "workshop_create_file",
            """{"subfolderId":"$projectSubfolderId","fileName":"a/b.js","content":""}""",
        )
        assertTrue(result is ToolExecutionResult.Failure)
        assertTrue((result as ToolExecutionResult.Failure).message.contains("Invalid fileName"))
    }

    private suspend fun seedWorkshopFile(fileName: String, content: String): FileReference {
        val dir = File(workshopRoot, projectSubfolderId.toString()).also { it.mkdirs() }
        val diskFile = File(dir, fileName).also { it.writeText(content) }
        val refId = db.fileReferenceDao().insert(
            FileReference(
                subfolderId = projectSubfolderId,
                fileName = fileName,
                fileType = fileName.substringAfterLast('.', "txt"),
                filePath = diskFile.absolutePath,
            ),
        )
        return db.fileReferenceDao().getById(refId)!!
    }
}
