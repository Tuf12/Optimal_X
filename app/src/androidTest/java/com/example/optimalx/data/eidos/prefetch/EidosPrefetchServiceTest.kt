package com.example.optimalx.data.eidos.prefetch

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.eidos.prompt.EidosScopeProfileIds
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.semantic.EmbeddingEngine
import com.example.optimalx.data.semantic.SemanticChunkBuilder
import com.example.optimalx.data.semantic.SemanticIndexer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class EidosPrefetchServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var semanticIndexer: SemanticIndexer
    private lateinit var chunkBuilder: SemanticChunkBuilder
    private lateinit var service: EidosPrefetchService

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        semanticIndexer = SemanticIndexer(db, EmbeddingEngine(context))
        chunkBuilder = SemanticChunkBuilder(
            noteDao = db.noteDao(),
            subfolderDao = db.subfolderDao(),
            parentFolderDao = db.parentFolderDao(),
            conversationDao = db.conversationDao(),
            chatMessageDao = db.chatMessageDao(),
        )
        service = EidosPrefetchService(db, semanticIndexer)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun prefetch_returnsLtmChunkForGeneralChatQuery() = runBlocking {
        seedLongTermMemory("User lives in Chicago and prefers metric measurements.")

        val result = service.prefetch(
            policy = EidosPrefetchPolicy.MAIN_CHAT,
            profileId = EidosScopeProfileIds.GENERAL_APP,
            query = "Where do I live and what units do I prefer?",
            subfolderId = null,
            parentFolderId = null,
        )

        assertNotNull(result.block)
        assertTrue(result.block!!.contains(EidosRetrievedContextBlock.SECTION_HEADER))
        assertTrue(result.block!!.contains("Chicago"))
        assertTrue(result.block!!.contains("LTM"))
        assertTrue((result.metrics.hitCount) > 0)
    }

    private suspend fun seedLongTermMemory(content: String) {
        val parentId = db.parentFolderDao().insert(
            ParentFolder(
                name = SystemFolderNames.EIDOS_MEMORY,
                sortOrder = 0,
                isSystemFolder = true,
            ),
        )
        val dateKey = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val subfolderId = db.subfolderDao().insert(
            Subfolder(parentFolderId = parentId, name = dateKey, sortOrder = 0),
        )
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        db.noteDao().insert(
            Note(
                subfolderId = subfolderId,
                content = "[$ts] $content",
            ),
        )
        chunkBuilder.indexNote(semanticIndexer, subfolderId)
    }
}
