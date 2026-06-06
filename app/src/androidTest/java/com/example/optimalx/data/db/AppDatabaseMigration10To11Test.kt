package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration10To11Test {

    @Test
    fun migration_10_11_recreates_web_search_indexes_for_room_validation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(10) {
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
            createVersion10SchemaWithPartialIndexes(db)

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 10 && it.endVersion == 11 }
            migration.migrate(db)

            assertNotNull(indexSql(db, "index_conversations_web_editor_search"))
            assertNotNull(indexSql(db, "index_conversations_web_widget_search"))
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private fun createVersion10SchemaWithPartialIndexes(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                scopeType TEXT NOT NULL,
                parentFolderId INTEGER,
                subfolderId INTEGER,
                title TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                webSearchKey TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX index_conversations_web_editor_search
            ON conversations(scopeType, subfolderId, webSearchKey)
            WHERE scopeType = 'web_editor' AND webSearchKey IS NOT NULL
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX index_conversations_web_widget_search
            ON conversations(scopeType, webSearchKey)
            WHERE scopeType = 'web_widget' AND webSearchKey IS NOT NULL
            """.trimIndent(),
        )
    }

    private fun indexSql(db: SupportSQLiteDatabase, indexName: String): String? {
        db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(indexName),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    companion object {
        private const val TEST_DB = "migration_10_11_test.db"
    }
}
