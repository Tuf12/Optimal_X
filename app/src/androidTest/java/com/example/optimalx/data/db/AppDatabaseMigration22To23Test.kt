package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration22To23Test {

    @Test
    fun migration_22_23_drops_tag_hint_lines_table() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(22) {
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
                CREATE TABLE IF NOT EXISTS tag_hint_lines (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    ref TEXT NOT NULL,
                    objectType TEXT NOT NULL,
                    scopeType TEXT NOT NULL,
                    scopeId TEXT,
                    parentRef TEXT,
                    rootBranch TEXT NOT NULL,
                    tag TEXT NOT NULL,
                    hint TEXT NOT NULL,
                    objectName TEXT NOT NULL,
                    date TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_tag_hint_lines_ref ON tag_hint_lines(ref)",
            )

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 22 && it.endVersion == 23 }
            migration.migrate(db)

            assertFalse(tableExists(db, "tag_hint_lines"))
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    companion object {
        private const val TEST_DB = "migration_22_23_test.db"
    }
}
