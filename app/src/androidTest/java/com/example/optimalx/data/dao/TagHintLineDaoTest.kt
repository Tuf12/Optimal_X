package com.example.optimalx.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.TagHintLine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagHintLineDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TagHintLineDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.tagHintLineDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun upsertByRef_replaces_existing_ref() = runBlocking {
        val first = baseLine(ref = "note:482", hint = "tile bid numbers", tag = "tile bid")
        dao.upsertByRef(first)
        val second = baseLine(ref = "note:482", hint = "dock build plan", tag = "dock build")
        dao.upsertByRef(second)

        val row = dao.getByRef("note:482")
        assertNotNull(row)
        assertEquals("dock build plan", row?.hint)
        assertEquals("dock build", row?.tag)
        assertEquals(1, dao.getAll(limit = 100, offset = 0).size)
    }

    @Test
    fun query_apis_return_expected_rows() = runBlocking {
        dao.insert(baseLine(ref = "note:1", tag = "alpha", hint = "alpha hint", date = 1_000))
        dao.insert(baseLine(ref = "file:2", tag = "beta", hint = "beta hint", date = 2_000))
        dao.insert(baseLine(ref = "chat:general:3", tag = "gamma", hint = "gamma hint", date = 3_000))

        assertEquals(3, dao.getAll(limit = 100, offset = 0).size)
        assertEquals(1, dao.queryByTag("beta").size)
        assertEquals(2, dao.queryByDateRange(1_500, 3_500).size)
        assertEquals(1, dao.searchByQuery("gamma").size)
        assertEquals(3, dao.observeAll().first().size)
    }

    @Test
    fun deleteByRef_removes_row() = runBlocking {
        dao.insert(baseLine(ref = "quick_note:2026-05-03:1"))
        val deleted = dao.deleteByRef("quick_note:2026-05-03:1")
        assertEquals(1, deleted)
        assertTrue(dao.getAll(limit = 100, offset = 0).isEmpty())
    }

    private fun baseLine(
        ref: String,
        tag: String = "tag",
        hint: String = "hint",
        date: Long = 1_700_000_000_000L,
        objectName: String = "Test Object",
        parentFolderName: String? = "Work",
        subfolderName: String? = "Tile Bid",
    ): TagHintLine {
        return TagHintLine(
            ref = ref,
            objectType = ref.substringBefore(":"),
            scopeType = "none",
            scopeId = null,
            parentRef = null,
            rootBranch = "hierarchy",
            tag = tag,
            hint = hint,
            objectName = objectName,
            parentFolderName = parentFolderName,
            subfolderName = subfolderName,
            date = date,
            createdAt = date,
            updatedAt = date,
        )
    }
}
