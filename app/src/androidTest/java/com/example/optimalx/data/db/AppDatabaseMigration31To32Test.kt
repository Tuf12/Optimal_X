package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration31To32Test {

    @Test
    fun migration_31_32_adds_deletedAt_to_file_references() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(31) {
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
                CREATE TABLE IF NOT EXISTS file_references (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    subfolderId INTEGER NOT NULL,
                    fileName TEXT NOT NULL,
                    fileType TEXT NOT NULL,
                    filePath TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    globalId TEXT NOT NULL,
                    originDeviceId TEXT,
                    metadataJson TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO file_references (
                    subfolderId, fileName, fileType, filePath, createdAt, globalId
                ) VALUES (1, 'cover.png', 'image', '/tmp/cover.png', 1000, 'file-global-1')
                """.trimIndent(),
            )
        } finally {
            db.close()
        }

        val helper2 = FrameworkSQLiteOpenHelperFactory().create(config)
        val db2 = helper2.writableDatabase
        try {
            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 31 && it.endVersion == 32 }
            migration.migrate(db2)

            db2.query("PRAGMA table_info(file_references)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(names.contains("deletedAt"))
            }

            db2.execSQL(
                "UPDATE file_references SET deletedAt = 2000 WHERE id = 1",
            )
            db2.query("SELECT deletedAt FROM file_references WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNotNull(cursor.getLong(0))
            }
        } finally {
            db2.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    companion object {
        private const val TEST_DB = "migration_31_32_test"
    }
}
