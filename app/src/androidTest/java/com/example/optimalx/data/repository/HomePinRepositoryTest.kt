package com.example.optimalx.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.repository.HomePinRepository
import com.example.optimalx.ui.folders.HomePinType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomePinRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: HomePinRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HomePinRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun pinParent_isIdempotent() = runBlocking {
        val parentId = db.parentFolderDao().insert(ParentFolder(name = "Jobs"))

        repository.pin(HomePinType.PARENT, parentId, "Jobs")
        repository.pin(HomePinType.PARENT, parentId, "Jobs")

        val pins = db.homePinDao().getAllOnce()
        assertEquals(1, pins.size)
    }

    @Test
    fun observeUserPins_resolvesCurrentName() = runBlocking {
        val parentId = db.parentFolderDao().insert(ParentFolder(name = "Old name"))
        repository.pin(HomePinType.PARENT, parentId, "Old name")
        db.parentFolderDao().update(
            db.parentFolderDao().getById(parentId)!!.copy(name = "Renamed"),
        )

        val pins = repository.observeUserPins().first()

        assertEquals(1, pins.size)
        assertEquals("Renamed", pins.single().label)
        assertEquals(HomePinType.PARENT, pins.single().pinType)
    }

    @Test
    fun observeUserPins_prunesDeletedParent() = runBlocking {
        val parentId = db.parentFolderDao().insert(ParentFolder(name = "Temp"))
        repository.pin(HomePinType.PARENT, parentId, "Temp")
        db.parentFolderDao().softDelete(parentId)

        val pins = repository.observeUserPins().first()

        assertTrue(pins.isEmpty())
        assertTrue(db.homePinDao().getAllOnce().isEmpty())
    }

    @Test
    fun onParentFolderDeleted_removesParentAndSubfolderPins() = runBlocking {
        val parentId = db.parentFolderDao().insert(ParentFolder(name = "Jobs"))
        val subfolderId = db.subfolderDao().insert(Subfolder(parentFolderId = parentId, name = "Bid A"))
        repository.pin(HomePinType.PARENT, parentId, "Jobs")
        repository.pin(HomePinType.SUBFOLDER, subfolderId, "Bid A")

        repository.onParentFolderDeleted(parentId)

        assertTrue(db.homePinDao().getAllOnce().isEmpty())
    }

    @Test
    fun panelPin_requiresWorkshopParent() = runBlocking {
        val workshopParentId = db.parentFolderDao().insert(
            ParentFolder(name = SystemFolderNames.PANEL_WORKSHOP, isSystemFolder = true),
        )
        val projectId = db.subfolderDao().insert(
            Subfolder(parentFolderId = workshopParentId, name = "Calculator"),
        )
        val userParentId = db.parentFolderDao().insert(ParentFolder(name = "User"))
        val wrongSubfolderId = db.subfolderDao().insert(
            Subfolder(parentFolderId = userParentId, name = "Note"),
        )

        repository.pin(HomePinType.PANEL, projectId, "Calculator")
        repository.pin(HomePinType.PANEL, wrongSubfolderId, "Note")

        val pins = repository.observeUserPins().first()

        assertEquals(1, pins.size)
        assertEquals(HomePinType.PANEL, pins.single().pinType)
        assertEquals(projectId, pins.single().targetId)
    }
}
