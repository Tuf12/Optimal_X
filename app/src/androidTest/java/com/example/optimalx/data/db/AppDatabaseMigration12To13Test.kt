package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration12To13Test {

    @Test
    fun migration_12_13_adds_conversation_memory_depth() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(12) {
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

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 12 && it.endVersion == 13 }
            migration.migrate(db)

            db.query("PRAGMA table_info(conversations)").use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (name == "memoryDepth") {
                        found = true
                        break
                    }
                }
                assertTrue(found)
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    companion object {
        private const val TEST_DB = "migration_12_13_test.db"
    }
}
