package com.example.optimalx.data.semantic

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.FileTextExtractor
import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticMaterializerTest {

    private lateinit var db: AppDatabase
    private lateinit var materializer: SemanticMaterializer

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val embeddingEngine = EmbeddingEngine(context)
        val indexer = SemanticIndexer(db, embeddingEngine)
        val chunkBuilder = SemanticChunkBuilder(
            noteDao = db.noteDao(),
            subfolderDao = db.subfolderDao(),
            parentFolderDao = db.parentFolderDao(),
            conversationDao = db.conversationDao(),
            chatMessageDao = db.chatMessageDao(),
        )
        materializer = SemanticMaterializer(
            db = db,
            semanticIndexer = indexer,
            chunkBuilder = chunkBuilder,
            fileTextExtractor = FileTextExtractor(context),
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun bootstrap_indexes_note_chunks() = runBlocking {
        val parentId = db.parentFolderDao().insert(
            ParentFolder(name = "Jobs", sortOrder = 0, isSystemFolder = false),
        )
        val subfolderId = db.subfolderDao().insert(
            Subfolder(parentFolderId = parentId, name = "Tile work", sortOrder = 0),
        )
        db.noteDao().insert(
            Note(
                subfolderId = subfolderId,
                content = "# Grout\n\nKitchen backsplash uses slate gray grout.\n\n# Tile\n\nSubway tile layout.",
            ),
        )

        materializer.bootstrapFullEmbeddings()

        val chunks = db.semanticChunkDao().getAll()
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.objectType == SemanticObjectType.NOTE })
        assertTrue(chunks.any { it.chunkText.contains("slate gray", ignoreCase = true) })
    }

    @Test
    fun syncForReason_indexesSingleNoteIncrementally() = runBlocking {
        val parentId = db.parentFolderDao().insert(
            ParentFolder(name = "Jobs", sortOrder = 0, isSystemFolder = false),
        )
        val subfolderId = db.subfolderDao().insert(
            Subfolder(parentFolderId = parentId, name = "Tile work", sortOrder = 0),
        )
        db.noteDao().insert(
            Note(
                subfolderId = subfolderId,
                content = "Kitchen backsplash uses slate gray grout.",
            ),
        )

        materializer.syncForReason("save_note_content:$subfolderId")

        val chunks = db.semanticChunkDao().getAll()
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.objectType == SemanticObjectType.NOTE && it.objectId == subfolderId })
    }
}
