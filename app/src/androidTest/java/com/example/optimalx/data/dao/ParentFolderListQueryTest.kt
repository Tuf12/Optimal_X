package com.example.optimalx.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParentFolderListQueryTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getActiveUserFolders_excludesSystemParents() = runBlocking {
        db.parentFolderDao().insert(ParentFolder(name = "Projects", isSystemFolder = false))
        db.parentFolderDao().insert(ParentFolder(name = SystemFolderNames.QUICK_NOTES, isSystemFolder = true))
        db.parentFolderDao().insert(ParentFolder(name = SystemFolderNames.EIDOS_CHATS, isSystemFolder = true))
        db.parentFolderDao().insert(ParentFolder(name = SystemFolderNames.PANEL_WORKSHOP, isSystemFolder = true))

        val userFolders = db.parentFolderDao().getActiveUserFolders().first()

        assertEquals(1, userFolders.size)
        assertEquals("Projects", userFolders.single().name)
    }

    @Test
    fun getActiveByParent_excludesSystemSubfolders() = runBlocking {
        val parentId = db.parentFolderDao().insert(ParentFolder(name = "Jobs", isSystemFolder = false))
        db.subfolderDao().insert(Subfolder(parentFolderId = parentId, name = "Kitchen remodel"))
        db.subfolderDao().insert(
            Subfolder(
                parentFolderId = parentId,
                name = SystemFolderNames.CHATS_SUBFOLDER,
                isSystemSubfolder = true,
            ),
        )
        db.subfolderDao().insert(
            Subfolder(
                parentFolderId = parentId,
                name = SystemFolderNames.PARENT_REASONING_SUBFOLDER,
                isSystemSubfolder = true,
            ),
        )

        val visible = db.subfolderDao().getActiveByParent(parentId).first()

        assertEquals(1, visible.size)
        assertEquals("Kitchen remodel", visible.single().name)
    }

    @Test
    fun legacyWithChatsQuery_stillIncludesSystemParentsForBackwardCompat() = runBlocking {
        db.parentFolderDao().insert(ParentFolder(name = "Projects", isSystemFolder = false))
        db.parentFolderDao().insert(ParentFolder(name = SystemFolderNames.QUICK_NOTES, isSystemFolder = true))

        val legacy = db.parentFolderDao().getActiveParentFoldersWithChats().first()

        assertTrue(legacy.any { it.name == "Projects" })
        assertTrue(legacy.any { it.name == SystemFolderNames.QUICK_NOTES })
    }

    @Test
    fun getActiveUserFolders_doesNotIncludeQuickNotes() = runBlocking {
        db.parentFolderDao().insert(ParentFolder(name = SystemFolderNames.QUICK_NOTES, isSystemFolder = true))

        val userFolders = db.parentFolderDao().getActiveUserFolders().first()

        assertTrue(userFolders.isEmpty())
    }

    @Test
    fun newParentSubfolders_excludeChatsFromVisibleList() = runBlocking {
        val parentId = db.parentFolderDao().insert(ParentFolder(name = "New parent", isSystemFolder = false))
        db.subfolderDao().insert(
            Subfolder(
                parentFolderId = parentId,
                name = SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER,
                isSystemSubfolder = true,
                sortOrder = 9998,
            ),
        )

        val visible = db.subfolderDao().getActiveByParent(parentId).first()
        assertFalse(visible.any { it.name == SystemFolderNames.CHATS_SUBFOLDER })
    }
}
