package com.example.optimalx.data.eidos.agentbyte

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.eidos.EidosIndexFeature
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.eidos.model.EidosToolDefinition
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.model.TagHintLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TagHintIndexingServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: TagHintIndexingService
    private val runnerCalls = AtomicInteger(0)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setup() {
        EidosIndexFeature.enabledOverride = true
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val runner = TagHintIndexingRunner { prompt: String, _: List<EidosToolDefinition> ->
            runnerCalls.incrementAndGet()
            val ref = Regex("""Reference:\s*([^\n]+)""").find(prompt)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val preview = Regex("""Content preview:\s*([\s\S]+?)\n\nWrite a short tag""")
                .find(prompt)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                .orEmpty()
            if (ref.isNotBlank()) {
                val existing = db.tagHintLineDao().getByRef(ref)
                val now = System.currentTimeMillis()
                val tag = preview.split(Regex("\\s+")).filter { it.isNotBlank() }.take(3).joinToString(" ").ifBlank { "indexed" }
                db.tagHintLineDao().upsertByRef(
                    TagHintLine(
                        id = existing?.id ?: 0,
                        ref = ref,
                        objectType = ref.substringBefore(":"),
                        scopeType = "none",
                        scopeId = null,
                        parentRef = null,
                        rootBranch = "hierarchy",
                        tag = tag,
                        hint = preview.take(80).ifBlank { "indexed" },
                        objectName = tag,
                        date = existing?.date ?: now,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    )
                )
            }
            "ok"
        }
        service = TagHintIndexingService(
            db = db,
            tagHintLineDao = db.tagHintLineDao(),
            runner = runner,
            scope = serviceScope,
        )
    }

    @After
    fun teardown() {
        EidosIndexFeature.enabledOverride = null
        db.close()
    }

    @Test
    fun dedupe_lastWriteWins_for_same_ref_and_event() = runBlocking {
        service.enqueue(
            IndexingRequest(
                ref = "note:100",
                eventType = "update",
                contentPreview = "first preview",
            )
        )
        service.enqueue(
            IndexingRequest(
                ref = "note:100",
                eventType = "update",
                contentPreview = "second preview",
            )
        )

        awaitUntil { db.tagHintLineDao().getByRef("note:100")?.hint == "second preview" }
        assertEquals(1, runnerCalls.get())
    }

    @Test
    fun shortCircuit_delete_removes_without_runner() = runBlocking {
        db.tagHintLineDao().upsertByRef(
            TagHintLine(
                ref = "note:999",
                objectType = "note",
                scopeType = "none",
                rootBranch = "hierarchy",
                tag = "existing",
                hint = "existing",
                objectName = "Note",
                date = System.currentTimeMillis(),
            )
        )
        service.enqueue(
            IndexingRequest(
                ref = "note:999",
                eventType = "invalid_ref",
                contentPreview = "",
            )
        )
        awaitUntil { db.tagHintLineDao().getByRef("note:999") == null }
        assertEquals(0, runnerCalls.get())
    }

    @Test
    fun enrichment_runs_when_content_preview_is_unchanged() = runBlocking {
        service.enqueue(
            IndexingRequest(
                ref = "note:500",
                eventType = "update",
                contentPreview = "unchanged",
            )
        )
        awaitUntil { db.tagHintLineDao().getByRef("note:500")?.hint == "unchanged" }
        assertEquals(1, runnerCalls.get())
    }

    @Test
    fun bootstrap_populates_representative_refs() = runBlocking {
        seedBootstrapData()
        val enqueued = service.bootstrapFullIndex()
        assertTrue(enqueued > 0)
        awaitUntil { db.tagHintLineDao().getAll(500, 0).size >= 8 }

        val refs = db.tagHintLineDao().getAll(500, 0).map { it.ref }.toSet()
        assertTrue(refs.any { it.startsWith("parent:") })
        assertTrue(refs.any { it.startsWith("subfolder:") })
        assertTrue(refs.any { it.startsWith("note:") })
        assertTrue(refs.any { it.startsWith("file:") })
        assertTrue(refs.any { it.startsWith("chat:general:") })
        assertTrue(refs.any { it.startsWith("quick_note:") })
    }

    private suspend fun seedBootstrapData() {
        val userParentId = db.parentFolderDao().insert(ParentFolder(name = "Projects", isSystemFolder = false))
        val userSubfolderId = db.subfolderDao().insert(Subfolder(parentFolderId = userParentId, name = "Alpha"))
        db.noteDao().insert(Note(subfolderId = userSubfolderId, content = "Build plan draft"))
        db.fileReferenceDao().insert(
            FileReference(
                subfolderId = userSubfolderId,
                fileName = "spec.md",
                fileType = "md",
                filePath = "/tmp/spec.md",
            )
        )
        db.conversationDao().insert(
            Conversation(
                scopeType = "general",
                title = "General chat",
            )
        )

        val quickNotesParentId = db.parentFolderDao().insert(ParentFolder(name = SystemFolderNames.QUICK_NOTES, isSystemFolder = true))
        val quickDayId = db.subfolderDao().insert(Subfolder(parentFolderId = quickNotesParentId, name = "2026-05-04"))
        db.noteDao().insert(Note(subfolderId = quickDayId, content = "[10:00] quick note line"))
    }

    private suspend fun awaitUntil(timeoutMs: Long = 3000, block: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (block()) return
            delay(50)
        }
        throw AssertionError("Condition not met within timeout")
    }
}
