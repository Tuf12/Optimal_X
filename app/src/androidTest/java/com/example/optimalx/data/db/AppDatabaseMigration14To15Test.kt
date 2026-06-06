package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration14To15Test {

    @Test
    fun migration_14_15_replaces_piece_lens_with_tag_and_names() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(14) {
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
            val now = System.currentTimeMillis()
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
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO tag_hint_lines (
                    id, ref, objectType, scopeType, scopeId, parentRef, rootBranch,
                    piece, lens, hint, date, createdAt, updatedAt
                ) VALUES (
                    1, 'note:500', 'note', 'none', NULL, 'subfolder:700', 'hierarchy',
                    'ROOK', 'logical', 'tile bid numbers', $now, $now, $now
                )
                """.trimIndent(),
            )

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 14 && it.endVersion == 15 }
            migration.migrate(db)

            db.query("PRAGMA table_info(tag_hint_lines)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(names.containsAll(listOf("tag", "hint", "objectName", "parentFolderName", "subfolderName")))
                assertFalse(names.contains("piece"))
                assertFalse(names.contains("lens"))
            }

            db.query("SELECT tag, hint, objectName FROM tag_hint_lines WHERE ref = 'note:500'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("tile bid numbers", cursor.getString(0))
                assertEquals("tile bid numbers", cursor.getString(1))
                assertEquals("", cursor.getString(2))
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private companion object {
        const val TEST_DB = "migration_14_15_test.db"
    }
}
