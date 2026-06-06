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
class AppDatabaseMigration8To9Test {

    @Test
    fun migration_8_9_adds_aiBlind_column_and_scrubs_blind_artifacts() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(8) {
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
            createVersion8Schema(db)
            seedVersion8Data(db)

            // Sanity check: aiBlind does not exist before migration.
            assertEquals(0, columnExistsCount(db, "notes", "aiBlind"))

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 8 && it.endVersion == 9 }
            migration.migrate(db)

            assertEquals(1, columnExistsCount(db, "notes", "aiBlind"))
            // Existing rows default to 0 (not blind).
            assertEquals(0, count(db, "SELECT aiBlind FROM notes WHERE id = 500"))
            assertEquals(0, count(db, "SELECT aiBlind FROM notes WHERE id = 501"))

            // No notes are flagged blind by the migration itself, so nothing should be scrubbed.
            assertEquals(1, count(db, "SELECT COUNT(*) FROM semantic_vectors WHERE sourceType = 'note' AND sourceId = 500"))
            assertEquals(1, count(db, "SELECT COUNT(*) FROM tag_hint_lines WHERE ref = 'note:500'"))

            // Now flip a row to blind, and verify the scrub statements are valid by re-running them.
            db.execSQL("UPDATE notes SET aiBlind = 1 WHERE id = 500")
            db.execSQL(
                """
                DELETE FROM semantic_vectors
                WHERE sourceType = 'note'
                  AND sourceId IN (SELECT subfolderId FROM notes WHERE aiBlind = 1)
                """.trimIndent()
            )
            db.execSQL(
                """
                DELETE FROM tag_hint_lines
                WHERE objectType = 'note'
                  AND ref IN (SELECT 'note:' || id FROM notes WHERE aiBlind = 1)
                """.trimIndent()
            )
            assertEquals(0, count(db, "SELECT COUNT(*) FROM semantic_vectors WHERE sourceType = 'note' AND sourceId = 500"))
            assertEquals(0, count(db, "SELECT COUNT(*) FROM tag_hint_lines WHERE ref = 'note:500'"))
            // Untouched rows survive.
            assertEquals(1, count(db, "SELECT COUNT(*) FROM tag_hint_lines WHERE ref = 'note:501'"))

            assertTrue(true)
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private fun createVersion8Schema(db: SupportSQLiteDatabase) {
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
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tag_hint_lines (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              ref TEXT NOT NULL,
              objectType TEXT NOT NULL,
              scopeType TEXT NOT NULL,
              scopeId TEXT,
              parentRef TEXT,
              rootBranch TEXT NOT NULL,
              piece TEXT NOT NULL,
              lens TEXT NOT NULL,
              hint TEXT NOT NULL,
              date INTEGER NOT NULL,
              createdAt INTEGER NOT NULL,
              updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun seedVersion8Data(db: SupportSQLiteDatabase) {
        val now = 1_700_000_000_000L
        db.execSQL("INSERT INTO notes (id, subfolderId, content, createdAt, updatedAt, deletedAt, aiLocked) VALUES (500, 700, 'private content', $now, $now, NULL, 0)")
        db.execSQL("INSERT INTO notes (id, subfolderId, content, createdAt, updatedAt, deletedAt, aiLocked) VALUES (501, 701, 'public content', $now, $now, NULL, 0)")
        db.execSQL("INSERT INTO semantic_vectors (id, sourceType, sourceId, embeddingBlob, updatedAt) VALUES (600, 'note', 700, X'00', $now)")
        db.execSQL("INSERT INTO tag_hint_lines (id, ref, objectType, scopeType, scopeId, parentRef, rootBranch, piece, lens, hint, date, createdAt, updatedAt) VALUES (800, 'note:500', 'note', 'none', NULL, 'subfolder:700', 'hierarchy', 'ROOK', 'logical', 'hint', $now, $now, $now)")
        db.execSQL("INSERT INTO tag_hint_lines (id, ref, objectType, scopeType, scopeId, parentRef, rootBranch, piece, lens, hint, date, createdAt, updatedAt) VALUES (801, 'note:501', 'note', 'none', NULL, 'subfolder:701', 'hierarchy', 'ROOK', 'logical', 'hint', $now, $now, $now)")
    }

    private fun columnExistsCount(db: SupportSQLiteDatabase, table: String, column: String): Int {
        var found = 0
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == column) {
                    found = 1
                    break
                }
            }
        }
        return found
    }

    private fun count(db: SupportSQLiteDatabase, sql: String): Int {
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test-optimalx-8to9.db"
    }
}
