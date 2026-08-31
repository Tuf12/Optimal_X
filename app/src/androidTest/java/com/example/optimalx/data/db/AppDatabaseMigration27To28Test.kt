package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.sync.SyncContentHash
import com.example.optimalx.data.sync.SyncGlobalIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration27To28Test {

    @Test
    fun migration_27_28_adds_sync_columns_and_system_global_ids() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(27) {
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
            createV27Schema(db)
            seedV27Rows(db)

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 27 && it.endVersion == 28 }
            migration.migrate(db)

            assertColumnExists(db, "parent_folders", "globalId")
            assertColumnExists(db, "subfolders", "targetPlatform")
            assertColumnExists(db, "notes", "contentHash")

            db.query(
                "SELECT globalId FROM parent_folders WHERE name = ?",
                arrayOf(SystemFolderNames.QUICK_NOTES),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    SyncGlobalIds.systemParentGlobalId(SystemFolderNames.QUICK_NOTES),
                    cursor.getString(0),
                )
            }

            db.query(
                "SELECT globalId, contentHash FROM notes WHERE subfolderId = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0).isNotBlank())
                assertEquals(SyncContentHash.noteContentHash("hello"), cursor.getString(1))
            }

            db.query(
                "SELECT targetPlatform FROM subfolders WHERE id = 2",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("mobile", cursor.getString(0))
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private fun createV27Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE parent_folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL,
                deletedAt INTEGER,
                isSystemFolder INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE subfolders (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                parentFolderId INTEGER NOT NULL,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL,
                deletedAt INTEGER,
                isSystemSubfolder INTEGER NOT NULL,
                projectSummary TEXT,
                projectSummaryUpdatedAt INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE notes (
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
        listOf(
            "file_references",
            "home_pins",
            "conversations",
            "chat_messages",
            "custom_panel_assignments",
            "panel_state",
            "content_checkpoints",
            "content_patches",
            "pending_change_sets",
            "pending_change_items",
        ).forEach { table ->
            db.execSQL("CREATE TABLE IF NOT EXISTS $table (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        }
        db.execSQL(
            """
            CREATE TABLE panel_state (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                workshopSubfolderId INTEGER NOT NULL,
                scopeKey TEXT NOT NULL,
                stateJson TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun seedV27Rows(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO parent_folders (id, name, createdAt, updatedAt, sortOrder, isSystemFolder)
            VALUES (1, '${SystemFolderNames.QUICK_NOTES}', 0, 0, 0, 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO parent_folders (id, name, createdAt, updatedAt, sortOrder, isSystemFolder)
            VALUES (2, '${SystemFolderNames.PANEL_WORKSHOP}', 0, 0, 1, 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO subfolders (id, parentFolderId, name, createdAt, updatedAt, sortOrder, isSystemSubfolder)
            VALUES (1, 1, '2026-07-07', 0, 0, 0, 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO subfolders (id, parentFolderId, name, createdAt, updatedAt, sortOrder, isSystemSubfolder)
            VALUES (2, 2, 'My Panel', 0, 0, 0, 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO notes (id, subfolderId, content, createdAt, updatedAt, aiLocked, aiBlind)
            VALUES (1, 1, 'hello', 0, 0, 0, 0)
            """.trimIndent(),
        )
    }

    private fun assertColumnExists(db: SupportSQLiteDatabase, table: String, column: String) {
        db.query("PRAGMA table_info($table)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue("$table missing $column", columns.contains(column))
        }
    }

    companion object {
        private const val TEST_DB = "migration_27_28_test"
    }
}
