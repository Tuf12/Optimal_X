package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.eidos.NoteSummaryCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration24To25Test {

    @Test
    fun migration_24_25_copies_memory_cache_into_empty_note_memory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(24) {
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
                CREATE TABLE IF NOT EXISTS parent_folders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    isSystemFolder INTEGER NOT NULL,
                    deletedAt INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS subfolders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    parentFolderId INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    isSystemSubfolder INTEGER NOT NULL,
                    deletedAt INTEGER
                )
                """.trimIndent(),
            )
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
                    summaryUpdatedAt INTEGER,
                    summaryContentWatermark INTEGER
                )
                """.trimIndent(),
            )

            db.execSQL("INSERT INTO parent_folders (id, name, sortOrder, isSystemFolder) VALUES (1, 'Projects', 0, 0)")
            db.execSQL(
                """
                INSERT INTO subfolders (id, parentFolderId, name, createdAt, updatedAt, sortOrder, isSystemSubfolder)
                VALUES (10, 1, 'Kitchen', 0, 0, 0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO subfolders (id, parentFolderId, name, createdAt, updatedAt, sortOrder, isSystemSubfolder)
                VALUES (20, 1, 'Memory Cache', 0, 0, 9998, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO notes (id, subfolderId, content, createdAt, updatedAt, deletedAt, aiLocked, aiBlind)
                VALUES (1, 10, 'note body', 0, 0, NULL, 0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO notes (id, subfolderId, content, createdAt, updatedAt, deletedAt, aiLocked, aiBlind)
                VALUES (2, 20, '{"10":"Prefer matte grout."}', 0, 0, NULL, 0, 0)
                """.trimIndent(),
            )

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 24 && it.endVersion == 25 }
            migration.migrate(db)

            db.query("SELECT summary FROM notes WHERE subfolderId = 10").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val parsed = NoteSummaryCodec.parse(cursor.getString(0))
                assertEquals(listOf("Prefer matte grout."), parsed.memoryBullets)
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    companion object {
        private const val TEST_DB = "migration_24_25_test"
    }
}
