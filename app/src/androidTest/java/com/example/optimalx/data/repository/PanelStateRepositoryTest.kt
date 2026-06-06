package com.example.optimalx.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.eidos.PanelStateScope
import com.example.optimalx.data.eidos.PanelStateScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PanelStateRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: PanelStateRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppDatabase.closeAndClearInstance()
        db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PanelStateRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        AppDatabase.closeAndClearInstance()
    }

    @Test
    fun saveAndLoad_globalScope() = runBlocking {
        repository.saveStateJson(42L, PanelStateScope.GLOBAL, """{"score":3}""")
        assertEquals("""{"score":3}""", repository.loadStateJson(42L, PanelStateScope.GLOBAL))
    }

    @Test
    fun scopesAreIsolated() = runBlocking {
        repository.saveStateJson(42L, PanelStateScope.GLOBAL, """{"mode":"gallery"}""")
        repository.saveStateJson(42L, PanelStateScope.forHostSubfolder(7L), """{"mode":"editor"}""")

        assertEquals("""{"mode":"gallery"}""", repository.loadStateJson(42L, PanelStateScope.GLOBAL))
        assertEquals("""{"mode":"editor"}""", repository.loadStateJson(42L, PanelStateScope.forHostSubfolder(7L)))
    }

    @Test
    fun updateExistingScope_overwritesJson() = runBlocking {
        repository.saveStateJson(42L, PanelStateScope.GLOBAL, """{"score":1}""")
        repository.saveStateJson(42L, PanelStateScope.GLOBAL, """{"score":2}""")

        assertEquals("""{"score":2}""", repository.loadStateJson(42L, PanelStateScope.GLOBAL))
        val rows = db.panelStateDao().getStateJson(42L, PanelStateScope.GLOBAL)
        assertEquals("""{"score":2}""", rows)
    }

    @Test
    fun deleteForWorkshopProject_removesAllScopes() = runBlocking {
        repository.saveStateJson(42L, PanelStateScope.GLOBAL, """{"a":1}""")
        repository.saveStateJson(42L, PanelStateScope.forHostSubfolder(1L), """{"b":2}""")

        repository.deleteForWorkshopProject(42L)

        assertEquals("{}", repository.loadStateJson(42L, PanelStateScope.GLOBAL))
        assertNull(db.panelStateDao().getStateJson(42L, PanelStateScope.forHostSubfolder(1L)))
    }
}
