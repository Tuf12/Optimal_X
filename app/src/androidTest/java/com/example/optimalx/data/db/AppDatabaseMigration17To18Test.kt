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

/**
 * Verifies the DIFF_REVIEW v1 schema: content_checkpoints, content_patches,
 * pending_change_sets, and pending_change_items tables + their indexes are
 * created on upgrade, and that the tables accept the row shapes the entities
 * produce.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration17To18Test {

    @Test
    fun migration_17_18_creates_diff_review_tables_and_indexes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(17) {
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
            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 17 && it.endVersion == 18 }
            migration.migrate(db)

            assertColumns(
                db,
                table = "content_checkpoints",
                expected = listOf(
                    "id",
                    "sourceType",
                    "sourceId",
                    "sequence",
                    "contentBlob",
                    "contentHash",
                    "author",
                    "label",
                    "conversationId",
                    "createdAt",
                ),
            )
            assertIndexes(
                db,
                table = "content_checkpoints",
                expected = listOf(
                    "index_content_checkpoints_sourceType_sourceId_sequence",
                    "index_content_checkpoints_sourceType_sourceId_createdAt",
                ),
            )

            assertColumns(
                db,
                table = "content_patches",
                expected = listOf(
                    "id",
                    "sourceType",
                    "sourceId",
                    "fromCheckpointId",
                    "toCheckpointId",
                    "unifiedDiff",
                    "createdAt",
                ),
            )
            assertIndexes(
                db,
                table = "content_patches",
                expected = listOf(
                    "index_content_patches_sourceType_sourceId",
                    "index_content_patches_fromCheckpointId",
                    "index_content_patches_toCheckpointId",
                ),
            )

            assertColumns(
                db,
                table = "pending_change_sets",
                expected = listOf(
                    "id",
                    "scopeType",
                    "scopeId",
                    "conversationId",
                    "status",
                    "createdAt",
                    "updatedAt",
                ),
            )
            assertIndexes(
                db,
                table = "pending_change_sets",
                expected = listOf(
                    "index_pending_change_sets_scopeType_scopeId",
                    "index_pending_change_sets_status",
                    "index_pending_change_sets_conversationId",
                ),
            )

            assertColumns(
                db,
                table = "pending_change_items",
                expected = listOf(
                    "id",
                    "changeSetId",
                    "sourceType",
                    "sourceId",
                    "baseCheckpointId",
                    "proposedContent",
                    "proposedHash",
                    "unifiedDiff",
                    "status",
                    "fileName",
                    "isNewFile",
                    "createdAt",
                    "updatedAt",
                ),
            )
            assertIndexes(
                db,
                table = "pending_change_items",
                expected = listOf(
                    "index_pending_change_items_changeSetId",
                    "index_pending_change_items_sourceType_sourceId",
                    "index_pending_change_items_status",
                ),
            )

            db.execSQL(
                """
                INSERT INTO content_checkpoints (
                    sourceType, sourceId, sequence, contentBlob, contentHash, author,
                    label, conversationId, createdAt
                ) VALUES (
                    'workshop_file', 1, 0, 'hello world', 'abc123', 'system',
                    'baseline', NULL, 1000
                )
                """.trimIndent(),
            )
            db.query("SELECT COUNT(*) FROM content_checkpoints").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }

            db.execSQL(
                """
                INSERT INTO pending_change_sets (
                    scopeType, scopeId, conversationId, status, createdAt, updatedAt
                ) VALUES (
                    'workshop_project', 1, 7, 'open', 2000, 2000
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO pending_change_items (
                    changeSetId, sourceType, sourceId, baseCheckpointId,
                    proposedContent, proposedHash, unifiedDiff, status,
                    fileName, isNewFile, createdAt, updatedAt
                ) VALUES (
                    1, 'workshop_file', 1, 1,
                    'hello world!', 'def456', '@@ -1 +1 @@\n-hello world\n+hello world!', 'pending',
                    'index.html', 0, 2001, 2001
                )
                """.trimIndent(),
            )
            db.query(
                "SELECT status, fileName, isNewFile FROM pending_change_items WHERE changeSetId = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("pending", cursor.getString(0))
                assertEquals("index.html", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
            }

            // ON DELETE CASCADE: deleting the set wipes its items.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM pending_change_sets WHERE id = 1")
            db.query("SELECT COUNT(*) FROM pending_change_items WHERE changeSetId = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private fun assertColumns(db: SupportSQLiteDatabase, table: String, expected: List<String>) {
        db.query("PRAGMA table_info($table)").use { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue(
                "$table missing columns. expected=$expected actual=$names",
                names.containsAll(expected),
            )
        }
    }

    private fun assertIndexes(db: SupportSQLiteDatabase, table: String, expected: List<String>) {
        db.query("PRAGMA index_list($table)").use { cursor ->
            val indexes = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                indexes.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue(
                "$table missing indexes. expected=$expected actual=$indexes",
                indexes.containsAll(expected),
            )
        }
    }

    private companion object {
        const val TEST_DB = "migration_17_18_test.db"
    }
}
