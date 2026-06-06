package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration16To17Test {

    @Test
    fun migration_16_17_adds_assistantReasoningContent_to_chat_messages() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(16) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    conversationId INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chat_messages (conversationId, role, content, createdAt)
                VALUES (1, 'eidos', 'Hello', 1000)
                """.trimIndent(),
            )

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 16 && it.endVersion == 17 }
            migration.migrate(db)

            db.query("PRAGMA table_info(chat_messages)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(names.contains("assistantReasoningContent"))
            }

            db.query(
                "SELECT assistantReasoningContent FROM chat_messages WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNull(cursor.getString(0))
            }

            db.execSQL(
                """
                UPDATE chat_messages
                SET assistantReasoningContent = 'step one reasoning'
                WHERE id = 1
                """.trimIndent(),
            )
            db.query(
                "SELECT assistantReasoningContent FROM chat_messages WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("step one reasoning", cursor.getString(0))
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private companion object {
        const val TEST_DB = "migration_16_17_test.db"
    }
}
