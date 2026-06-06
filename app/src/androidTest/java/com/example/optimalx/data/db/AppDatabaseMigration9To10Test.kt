package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration9To10Test {

    @Test
    fun migration_9_10_adds_webSearchKey_and_unique_indexes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(9) {
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
            createVersion9Schema(db)
            assertEquals(0, columnExistsCount(db, "conversations", "webSearchKey"))

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 9 && it.endVersion == 10 }
            migration.migrate(db)

            assertEquals(1, columnExistsCount(db, "conversations", "webSearchKey"))
            val editorIndexSql = indexSql(db, "index_conversations_web_editor_search")
            val widgetIndexSql = indexSql(db, "index_conversations_web_widget_search")
            assertNotNull(editorIndexSql)
            assertNotNull(widgetIndexSql)
            requireNotNull(editorIndexSql).contains("webSearchKey")
            requireNotNull(widgetIndexSql).contains("webSearchKey")

            db.execSQL(
                """
                INSERT INTO conversations (scopeType, parentFolderId, subfolderId, title, createdAt, updatedAt, webSearchKey)
                VALUES ('web_editor', NULL, 10, 'Kotlin', 1, 1, 'kotlin')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO conversations (scopeType, parentFolderId, subfolderId, title, createdAt, updatedAt, webSearchKey)
                VALUES ('web_widget', NULL, NULL, 'Weather', 2, 2, 'weather')
                """.trimIndent(),
            )
            assertEquals(1, count(db, "SELECT COUNT(*) FROM conversations WHERE scopeType = 'web_editor'"))
            assertEquals(1, count(db, "SELECT COUNT(*) FROM conversations WHERE scopeType = 'web_widget'"))
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private fun createVersion9Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                scopeType TEXT NOT NULL,
                parentFolderId INTEGER,
                subfolderId INTEGER,
                title TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun columnExistsCount(db: SupportSQLiteDatabase, table: String, column: String): Int {
        db.query("PRAGMA table_info($table)").use { cursor ->
            var found = 0
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) {
                    found = 1
                }
            }
            return found
        }
    }

    private fun indexSql(db: SupportSQLiteDatabase, indexName: String): String? {
        db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(indexName),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun count(db: SupportSQLiteDatabase, sql: String): Int {
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    companion object {
        private const val TEST_DB = "migration_9_10_test.db"
    }
}
