package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration7To8Test {

    @Test
    fun migration_7_8_creates_tag_hint_table_and_removes_legacy_index_subfolder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(7) {
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
            createVersion7Schema(db)
            seedLegacyIndexData(db)

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 7 && it.endVersion == 8 }
            migration.migrate(db)

            assertTrue(tableExists(db, "tag_hint_lines"))
            assertTrue(indexExists(db, "index_tag_hint_lines_ref"))

            assertEquals(0, count(db, "SELECT COUNT(*) FROM subfolders WHERE name = 'Tag & Hint Index'"))
            assertEquals(0, count(db, "SELECT COUNT(*) FROM notes WHERE subfolderId = 200"))
            assertEquals(0, count(db, "SELECT COUNT(*) FROM semantic_vectors WHERE sourceType = 'NOTE' AND sourceId = 200"))

            // Ensure unrelated content remains.
            assertEquals(1, count(db, "SELECT COUNT(*) FROM subfolders WHERE id = 201"))
            assertEquals(1, count(db, "SELECT COUNT(*) FROM notes WHERE subfolderId = 201"))
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private fun createVersion7Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS parent_folders (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              name TEXT NOT NULL,
              createdAt INTEGER NOT NULL,
              updatedAt INTEGER NOT NULL,
              sortOrder INTEGER NOT NULL,
              deletedAt INTEGER,
              isSystemFolder INTEGER NOT NULL
            )
            """.trimIndent()
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
            """.trimIndent()
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
              aiLocked INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS semantic_vectors (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              sourceType TEXT NOT NULL,
              sourceId INTEGER NOT NULL,
              embeddingBlob BLOB NOT NULL,
              updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun seedLegacyIndexData(db: SupportSQLiteDatabase) {
        val now = 1_700_000_000_000L
        db.execSQL("INSERT INTO parent_folders (id, name, createdAt, updatedAt, sortOrder, deletedAt, isSystemFolder) VALUES (100, 'Eidos Index', $now, $now, 1, NULL, 1)")
        db.execSQL("INSERT INTO parent_folders (id, name, createdAt, updatedAt, sortOrder, deletedAt, isSystemFolder) VALUES (101, 'Eidos Journal', $now, $now, 2, NULL, 1)")
        db.execSQL("INSERT INTO subfolders (id, parentFolderId, name, createdAt, updatedAt, sortOrder, isSystemSubfolder, deletedAt) VALUES (200, 100, 'Tag & Hint Index', $now, $now, 1, 1, NULL)")
        db.execSQL("INSERT INTO subfolders (id, parentFolderId, name, createdAt, updatedAt, sortOrder, isSystemSubfolder, deletedAt) VALUES (201, 101, '2026-05-03', $now, $now, 1, 1, NULL)")
        db.execSQL("INSERT INTO notes (id, subfolderId, content, createdAt, updatedAt, deletedAt, aiLocked) VALUES (300, 200, 'legacy line', $now, $now, NULL, 0)")
        db.execSQL("INSERT INTO notes (id, subfolderId, content, createdAt, updatedAt, deletedAt, aiLocked) VALUES (301, 201, 'keep me', $now, $now, NULL, 0)")
        db.execSQL("INSERT INTO semantic_vectors (id, sourceType, sourceId, embeddingBlob, updatedAt) VALUES (400, 'NOTE', 200, X'00', $now)")
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
        return count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table'") > 0
    }

    private fun indexExists(db: SupportSQLiteDatabase, index: String): Boolean {
        return count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='$index'") > 0
    }

    private fun count(db: SupportSQLiteDatabase, sql: String): Int {
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test-optimalx.db"
    }
}
