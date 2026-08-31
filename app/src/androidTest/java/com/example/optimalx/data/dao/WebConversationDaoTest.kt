package com.example.optimalx.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.Conversation
import com.example.optimalx.data.model.ConversationScopes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebConversationDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ConversationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .build()
        dao = db.conversationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun webEditorSearch_isolatedPerSubfolder() = runBlocking {
        dao.insert(
            Conversation(
                scopeType = ConversationScopes.WEB_EDITOR,
                subfolderId = 1L,
                webSearchKey = "kotlin",
                title = "Kotlin",
            ),
        )
        dao.insert(
            Conversation(
                scopeType = ConversationScopes.WEB_EDITOR,
                subfolderId = 2L,
                webSearchKey = "kotlin",
                title = "Kotlin",
            ),
        )
        assertNotNull(dao.getByWebEditorSearch(1L, "kotlin"))
        assertNotNull(dao.getByWebEditorSearch(2L, "kotlin"))
        assertEquals(2, dao.getRecentAll(10).size)
    }

    @Test
    fun getRecentMainChat_excludesWebScopes() = runBlocking {
        dao.insert(
            Conversation(scopeType = ConversationScopes.GENERAL, title = "General"),
        )
        dao.insert(
            Conversation(
                scopeType = ConversationScopes.WEB_WIDGET,
                webSearchKey = "weather",
                title = "Weather",
            ),
        )
        assertEquals(1, dao.getRecentMainChat(10).size)
        assertEquals(2, dao.getRecentAll(10).size)
    }

    @Test
    fun deleteWebWidgetSearch_removesRow() = runBlocking {
        dao.insert(
            Conversation(
                scopeType = ConversationScopes.WEB_WIDGET,
                webSearchKey = "cats",
                title = "Cats",
            ),
        )
        assertNotNull(dao.getByWebWidgetSearch("cats"))
        dao.deleteWebWidgetSearch("cats")
        assertNull(dao.getByWebWidgetSearch("cats"))
    }
}
