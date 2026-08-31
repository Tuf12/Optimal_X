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
class AppDatabaseMigration28To29Test {

    @Test
    fun migration_28_29_adds_navigationTargetsJson_to_chat_messages() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(28) {
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
                    assistantReasoningContent TEXT,
                    createdAt INTEGER NOT NULL,
                    globalId TEXT NOT NULL,
                    originDeviceId TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chat_messages (
                    conversationId, role, content, assistantReasoningContent, createdAt, globalId
                ) VALUES (1, 'eidos', 'Hello', NULL, 1000, 'msg-1')
                """.trimIndent(),
            )

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 28 && it.endVersion == 29 }
            migration.migrate(db)

            db.query("PRAGMA table_info(chat_messages)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(names.contains("navigationTargetsJson"))
            }

            db.query(
                "SELECT navigationTargetsJson FROM chat_messages WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNull(cursor.getString(0))
            }

            val json = """[{"kind":"note","label":"Trail map","subfolderId":3}]"""
            db.execSQL(
                """
                UPDATE chat_messages
                SET navigationTargetsJson = ?
                WHERE id = 1
                """.trimIndent(),
                arrayOf(json),
            )
            db.query(
                "SELECT navigationTargetsJson FROM chat_messages WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(json, cursor.getString(0))
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private companion object {
        const val TEST_DB = "migration_28_29_test.db"
    }
}
