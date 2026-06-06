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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for `workshop_replace_string` — the patch-style edit tool added in
 * Phase 2 of the DIFF_REVIEW + patch-editing rollout. Exercises the full RoomToolExecutor
 * + WorkshopWriteRouter + DirectWriteApplier / PendingChangeService pipeline against an
 * in-memory database and a temporary workshop dir on disk.
 */
@RunWith(AndroidJUnit4::class)
class RoomToolExecutorWorkshopReplaceStringTest {

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

    @Test
    fun replaceString_success_writesDiskAndCreatesCheckpoint_inBuildMode() = runBlocking {
        // Build mode auto-accepts — disk should be updated immediately.
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val ref = seedWorkshopFile(
            fileName = "script.js",
            content = "const x = 1;\nconst y = 2;\nconst z = 3;\n",
        )

        val result = executor.execute(
            "workshop_replace_string",
            """{"fileReferenceId":"${ref.id}","oldString":"const y = 2;","newString":"const y = 42;"}""",
        )

        assertTrue("expected Success, got $result", result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertTrue(success.modifiedSystem)
        assertTrue("message should mention update", success.content.contains("Updated"))
        // Disk should reflect the patched content.
        assertEquals(
            "const x = 1;\nconst y = 42;\nconst z = 3;\n",
            File(ref.filePath).readText(),
        )
        // A checkpoint should have been written for the new state (sequence > baseline).
        val latest = db.contentCheckpointDao().getLatest("workshop_file", ref.id)
        assertNotNull(latest)
        assertTrue("expected non-baseline checkpoint", (latest?.sequence ?: 0) >= 1)
    }

    @Test
    fun replaceString_inReviewPhase_queuesPendingItem_diskUntouched() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.EDIT,
            phase = WorkshopProjectPhase.UPDATE,
        )
        val ref = seedWorkshopFile(
            fileName = "script.js",
            content = "const x = 1;\nconst y = 2;\nconst z = 3;\n",
        )
        val before = File(ref.filePath).readText()

        val result = executor.execute(
            "workshop_replace_string",
            """{"fileReferenceId":"${ref.id}","oldString":"const y = 2;","newString":"const y = 42;"}""",
        )

        assertTrue("expected Success, got $result", result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertTrue(
            "review-phase result should mention proposal: ${success.content}",
            success.content.contains("Proposal queued for review"),
        )
        // Disk must be unchanged until accept.
        assertEquals(before, File(ref.filePath).readText())
        // One open pending item should exist for this file.
        val openSet = db.pendingChangeDao().findOpenSetForScope("workshop_project", projectSubfolderId)
        assertNotNull("an open set should exist", openSet)
        val items = db.pendingChangeDao().listItems(openSet!!.id)
        assertEquals(1, items.size)
        assertEquals(ref.id, items[0].sourceId)
        assertEquals("pending", items[0].status)
    }

    @Test
    fun replaceString_oldStringNotFound_returnsFailureWithSnippet() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val ref = seedWorkshopFile(
            fileName = "script.js",
            content = "const x = 1;\nconst y = 2;\nconst z = 3;\n",
        )

        val result = executor.execute(
            "workshop_replace_string",
            """{"fileReferenceId":"${ref.id}","oldString":"NOT_PRESENT","newString":"REPL"}""",
        )

        assertTrue("expected Failure, got $result", result is ToolExecutionResult.Failure)
        val failure = result as ToolExecutionResult.Failure
        assertTrue("error should mention not found: ${failure.message}", failure.message.contains("not found"))
        assertTrue("error should mention file name: ${failure.message}", failure.message.contains("script.js"))
        // Snippet must include line numbers so the LLM can re-anchor.
        assertTrue(
            "error should include line-numbered snippet: ${failure.message}",
            failure.message.contains("Current file (lines"),
        )
    }

    @Test
    fun replaceString_oldStringAmbiguous_returnsFailureWithCount() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val ref = seedWorkshopFile(
            fileName = "script.js",
            content = "value = 1;\nvalue = 1;\nvalue = 1;\n",
        )

        val result = executor.execute(
            "workshop_replace_string",
            """{"fileReferenceId":"${ref.id}","oldString":"value = 1;","newString":"value = 42;"}""",
        )

        assertTrue("expected Failure, got $result", result is ToolExecutionResult.Failure)
        val failure = result as ToolExecutionResult.Failure
        assertTrue(
            "error should mention 3 occurrences: ${failure.message}",
            failure.message.contains("3 occurrences"),
        )
        assertTrue(
            "error should mention must be unique: ${failure.message}",
            failure.message.contains("must be unique"),
        )
    }

    @Test
    fun replaceString_emptyOldString_returnsFailure() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val ref = seedWorkshopFile(fileName = "script.js", content = "let a = 1;")

        val result = executor.execute(
            "workshop_replace_string",
            """{"fileReferenceId":"${ref.id}","oldString":"","newString":"REPL"}""",
        )

        assertTrue(result is ToolExecutionResult.Failure)
        val failure = result as ToolExecutionResult.Failure
        assertTrue(failure.message.contains("non-empty"))
    }

    @Test
    fun replaceString_identicalOldAndNew_returnsFailure() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val ref = seedWorkshopFile(fileName = "script.js", content = "let a = 1;\n")

        val result = executor.execute(
            "workshop_replace_string",
            """{"fileReferenceId":"${ref.id}","oldString":"let a = 1;","newString":"let a = 1;"}""",
        )

        assertTrue(result is ToolExecutionResult.Failure)
        val failure = result as ToolExecutionResult.Failure
        assertTrue("error should mention identical: ${failure.message}", failure.message.contains("identical"))
    }

    @Test
    fun replaceString_inChatMode_isRejected_noPendingQueued() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.CHAT,
            phase = WorkshopProjectPhase.UPDATE,
        )
        val ref = seedWorkshopFile(
            fileName = "script.js",
            content = "const x = 1;\nconst y = 2;\n",
        )
        val before = File(ref.filePath).readText()

        val result = executor.execute(
            "workshop_replace_string",
            """{"fileReferenceId":"${ref.id}","oldString":"const y = 2;","newString":"const y = 42;"}""",
        )

        assertTrue("expected Failure, got $result", result is ToolExecutionResult.Failure)
        // Disk untouched and no pending set/item created.
        assertEquals(before, File(ref.filePath).readText())
        assertNull(db.pendingChangeDao().findOpenSetForScope("workshop_project", projectSubfolderId))
    }

    @Test
    fun replaceString_unknownFileReferenceId_returnsFailure() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val result = executor.execute(
            "workshop_replace_string",
            """{"fileReferenceId":"999999","oldString":"x","newString":"y"}""",
        )
        assertTrue(result is ToolExecutionResult.Failure)
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
