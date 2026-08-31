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
class AppDatabaseMigration15To16Test {

    @Test
    fun migration_15_16_creates_semantic_chunks_table_and_indexes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(15) {
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
            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 15 && it.endVersion == 16 }
            migration.migrate(db)

            db.query("PRAGMA table_info(semantic_chunks)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(
                    names.containsAll(
                        listOf(
                            "id",
                            "objectType",
                            "objectId",
                            "parentFolderId",
                            "subfolderId",
                            "location",
                            "chunkText",
                            "chunkType",
                            "startLine",
                            "endLine",
                            "embeddingBlob",
                            "updatedAt",
                        ),
                    ),
                )
            }

            db.query("PRAGMA index_list(semantic_chunks)").use { cursor ->
                val indexes = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    indexes.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(
                    indexes.containsAll(
                        listOf(
                            "index_semantic_chunks_objectType_objectId",
                            "index_semantic_chunks_subfolderId",
                            "index_semantic_chunks_parentFolderId",
                            "index_semantic_chunks_updatedAt",
                        ),
                    ),
                )
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private companion object {
        const val TEST_DB = "migration_15_16_test.db"
    }
}
