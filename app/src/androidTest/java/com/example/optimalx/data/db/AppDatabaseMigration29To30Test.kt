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
class AppDatabaseMigration29To30Test {

    @Test
    fun migration_29_30_adds_imageAttachmentJson_to_chat_messages() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(29) {
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
                    navigationTargetsJson TEXT,
                    createdAt INTEGER NOT NULL,
                    globalId TEXT NOT NULL,
                    originDeviceId TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chat_messages (
                    conversationId, role, content, assistantReasoningContent,
                    navigationTargetsJson, createdAt, globalId
                ) VALUES (1, 'user', 'Describe this image.', NULL, NULL, 1000, 'msg-1')
                """.trimIndent(),
            )

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 29 && it.endVersion == 30 }
            migration.migrate(db)

            db.query("PRAGMA table_info(chat_messages)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(names.contains("imageAttachmentJson"))
            }

            db.query(
                "SELECT imageAttachmentJson FROM chat_messages WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNull(cursor.getString(0))
            }

            val json = """{"fileName":"shot.png","mimeType":"image/png","storedName":"abc.png"}"""
            db.execSQL(
                """
                UPDATE chat_messages
                SET imageAttachmentJson = ?
                WHERE id = 1
                """.trimIndent(),
                arrayOf(json),
            )
            db.query(
                "SELECT imageAttachmentJson FROM chat_messages WHERE id = 1",
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
        const val TEST_DB = "migration_29_30_test.db"
    }
}
