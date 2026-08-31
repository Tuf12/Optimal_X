package com.example.optimalx.data.eidos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
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
 * Integration tests for `workshop_edit_file` and `workshop_append_file`.
 */
@RunWith(AndroidJUnit4::class)
class RoomToolExecutorWorkshopEditFileTest {

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
    fun editFile_success_writesDiskAndCreatesCheckpoint_inBuildMode() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val ref = seedWorkshopFile(
            fileName = "script.js",
            content = "const x = 1;\nconst y = 2;\nconst z = 3;\n",
        )

        val result = executor.execute(
            "workshop_edit_file",
            """{"fileReferenceId":"${ref.id}","startLine":2,"endLine":2,"newContent":"const y = 42;"}""",
        )

        assertTrue("expected Success, got $result", result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertTrue(success.modifiedSystem)
        assertTrue("message should mention update", success.content.contains("Updated"))
        assertEquals(
            "const x = 1;\nconst y = 42;\nconst z = 3;\n",
            File(ref.filePath).readText(),
        )
        val latest = db.contentCheckpointDao().getLatest("workshop_file", ref.id)
        assertNotNull(latest)
        assertTrue("expected non-baseline checkpoint", (latest?.sequence ?: 0) >= 1)
    }

    @Test
    fun editFile_inReviewPhase_queuesPendingItem_diskUntouched() = runBlocking {
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
            "workshop_edit_file",
            """{"fileReferenceId":"${ref.id}","startLine":2,"endLine":2,"newContent":"const y = 42;"}""",
        )

        assertTrue("expected Success, got $result", result is ToolExecutionResult.Success)
        val success = result as ToolExecutionResult.Success
        assertTrue(
            "review-phase result should mention proposal: ${success.content}",
            success.content.contains("Proposal queued for review"),
        )
        assertEquals(before, File(ref.filePath).readText())
        val openSet = db.pendingChangeDao().findOpenSetForScope("workshop_project", projectSubfolderId)
        assertNotNull("an open set should exist", openSet)
        val items = db.pendingChangeDao().listItems(openSet!!.id)
        assertEquals(1, items.size)
        assertEquals(ref.id, items[0].sourceId)
        assertEquals("pending", items[0].status)
    }

    @Test
    fun editFile_rangeBeyondEof_returnsFailure() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val ref = seedWorkshopFile(
            fileName = "script.js",
            content = "const x = 1;\nconst y = 2;\n",
        )

        val result = executor.execute(
            "workshop_edit_file",
            """{"fileReferenceId":"${ref.id}","startLine":99,"endLine":99,"newContent":"nope"}""",
        )

        assertTrue("expected Failure, got $result", result is ToolExecutionResult.Failure)
        val failure = result as ToolExecutionResult.Failure
        assertTrue(
            "error should mention beyond end: ${failure.message}",
            failure.message.contains("beyond") || failure.message.contains("lines"),
        )
    }

    @Test
    fun editFile_missingLines_returnsFailure() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val ref = seedWorkshopFile(fileName = "script.js", content = "let a = 1;")

        val result = executor.execute(
            "workshop_edit_file",
            """{"fileReferenceId":"${ref.id}","newContent":"REPL"}""",
        )

        assertTrue(result is ToolExecutionResult.Failure)
        val failure = result as ToolExecutionResult.Failure
        assertTrue(failure.message.contains("startLine"))
    }

    @Test
    fun appendFile_addsAtEof() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val ref = seedWorkshopFile(
            fileName = "FEATURES.md",
            content = "line1\nline2",
        )

        val result = executor.execute(
            "workshop_append_file",
            """{"fileReferenceId":"${ref.id}","content":"line3"}""",
        )

        assertTrue("expected Success, got $result", result is ToolExecutionResult.Success)
        assertEquals("line1\nline2\nline3", File(ref.filePath).readText())
    }

    @Test
    fun editFile_inChatMode_isRejected_noPendingQueued() = runBlocking {
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
            "workshop_edit_file",
            """{"fileReferenceId":"${ref.id}","startLine":2,"endLine":2,"newContent":"const y = 42;"}""",
        )

        assertTrue("expected Failure, got $result", result is ToolExecutionResult.Failure)
        assertEquals(before, File(ref.filePath).readText())
        assertNull(db.pendingChangeDao().findOpenSetForScope("workshop_project", projectSubfolderId))
    }

    @Test
    fun editFile_unknownFileReferenceId_returnsFailure() = runBlocking {
        WorkshopEidosSession.begin(
            mode = WorkshopEidosMode.BUILD_LOGIC,
            phase = WorkshopProjectPhase.LOGIC_BUILD,
        )
        val result = executor.execute(
            "workshop_edit_file",
            """{"fileReferenceId":"999999","startLine":1,"endLine":1,"newContent":"y"}""",
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
