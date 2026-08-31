package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.optimalx.data.db.SystemFolderNames.IMAGE_STUDIO
import com.example.optimalx.data.imagestudio.ImageStudioMetadata
import com.example.optimalx.data.imagestudio.ImageStudioSeed
import com.example.optimalx.data.sync.SyncGlobalIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration30To31Test {

    @Test
    fun migration_30_31_adds_metadataJson_and_seeds_image_studio() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(30) {
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
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    deletedAt INTEGER,
                    isSystemFolder INTEGER NOT NULL,
                    globalId TEXT NOT NULL,
                    originDeviceId TEXT
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
                    deletedAt INTEGER,
                    isSystemSubfolder INTEGER NOT NULL,
                    projectSummary TEXT,
                    projectSummaryUpdatedAt INTEGER,
                    targetPlatform TEXT NOT NULL,
                    globalId TEXT NOT NULL,
                    originDeviceId TEXT
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
                    summaryContentWatermark INTEGER,
                    globalId TEXT NOT NULL,
                    originDeviceId TEXT,
                    contentHash TEXT NOT NULL
                )
                """.trimIndent(),
            )
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
                    originDeviceId TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO subfolders (
                    parentFolderId, name, createdAt, updatedAt, sortOrder, deletedAt,
                    isSystemSubfolder, targetPlatform, globalId
                ) VALUES (1, 'Art', 1000, 1000, 0, NULL, 0, 'mobile', 'sub-art')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO file_references (
                    subfolderId, fileName, fileType, filePath, createdAt, globalId
                ) VALUES (1, 'cover.png', 'image', '/files/cover.png', 1000, 'file-1')
                """.trimIndent(),
            )

            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 30 && it.endVersion == 31 }
            migration.migrate(db)

            db.query("PRAGMA table_info(file_references)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(names.contains("metadataJson"))
            }

            val metadata = ImageStudioMetadata.build(
                prompt = "forest path",
                backend = "fal_cloud",
                modelId = "fal-ai/flux/schnell",
                tier = "draft",
                aspectRatio = "16:9",
                width = 1344,
                height = 768,
            )
            val metadataJson = ImageStudioMetadata.stringify(metadata)
            db.execSQL(
                "UPDATE file_references SET metadataJson = ? WHERE id = 1",
                arrayOf(metadataJson),
            )
            db.query("SELECT metadataJson FROM file_references WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(metadataJson, cursor.getString(0))
            }

            db.query(
                "SELECT name, globalId FROM parent_folders WHERE globalId = ?",
                arrayOf(SyncGlobalIds.IMAGE_STUDIO_PARENT),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(IMAGE_STUDIO, cursor.getString(0))
            }

            db.query(
                "SELECT name, globalId FROM subfolders WHERE globalId = ?",
                arrayOf(SyncGlobalIds.IMAGE_STUDIO_GENERAL_SUBFOLDER),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(ImageStudioSeed.GENERAL_SUBFOLDER_NAME, cursor.getString(0))
            }

            db.query(
                "SELECT id FROM notes WHERE subfolderId = (" +
                    "SELECT id FROM subfolders WHERE globalId = ? LIMIT 1)",
                arrayOf(SyncGlobalIds.IMAGE_STUDIO_GENERAL_SUBFOLDER),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private companion object {
        const val TEST_DB = "migration_30_31_test.db"
    }
}
