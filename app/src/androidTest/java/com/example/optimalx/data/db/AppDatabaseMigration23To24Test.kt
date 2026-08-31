package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.eidos.NoteSummaryCodec
import com.example.optimalx.data.eidos.NoteSummaryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration23To24Test {

    @Test
    fun migration_23_24_adds_watermark_and_migrates_legacy_summary() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(23) {
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
                CREATE TABLE IF NOT EXISTS notes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    subfolderId INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    deletedAt INTEGER,
                    aiLocked INTEGER NOT NULL,
                    aiBlind INTEGER NOT NULL,
                    summary TEXT,
                    summaryChunksJson TEXT,
                    summaryUpdatedAt INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO notes (
                    id, subfolderId, content, createdAt, updatedAt, deletedAt, aiLocked, aiBlind,
                    summary, summaryChunksJson, summaryUpdatedAt
                ) VALUES (1, 10, 'body', 0, 0, NULL, 0, 0, 'Legacy overview', NULL, 100)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO notes (
                    id, subfolderId, content, createdAt, updatedAt, deletedAt, aiLocked, aiBlind,
                    summary, summaryChunksJson, summaryUpdatedAt
                ) VALUES (2, 11, 'body2', 0, 0, NULL, 0, 0, 'Overview', 
                    '[{"anchor":"Part 1","text":"First section."}]', 100)
                """.trimIndent(),
            )

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 23 && it.endVersion == 24 }
            migration.migrate(db)

            db.query("PRAGMA table_info(notes)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(names.contains("summaryContentWatermark"))
            }

            db.query("SELECT summary, summaryChunksJson FROM notes WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val summary = cursor.getString(0)
                assertNull(cursor.getString(1))
                val parsed = NoteSummaryCodec.parse(summary)
                assertEquals("Legacy overview", parsed.contentDigest)
                assertTrue(summary!!.contains(NoteSummaryPolicy.CONTENT_HEADER))
            }

            db.query("SELECT summary, summaryChunksJson FROM notes WHERE id = 2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val summary = cursor.getString(0)
                assertNull(cursor.getString(1))
                val parsed = NoteSummaryCodec.parse(summary)
                assertTrue(parsed.contentDigest.contains("Overview"))
                assertTrue(parsed.contentDigest.contains("[Part 1]"))
                assertTrue(parsed.contentDigest.contains("First section."))
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    companion object {
        private const val TEST_DB = "migration_23_24_test.db"
    }
}
