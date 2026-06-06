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
class AppDatabaseMigration19To20Test {

    @Test
    fun migration_19_20_creates_panel_state_table() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(19) {
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
            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 19 && it.endVersion == 20 }
            migration.migrate(db)

            val cursor = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'panel_state'",
            )
            cursor.use {
                assertTrue(it.moveToFirst())
            }

            val indexCursor = db.query(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'index' AND name = 'index_panel_state_workshopSubfolderId_scopeKey'
                """.trimIndent(),
            )
            indexCursor.use {
                assertTrue(it.moveToFirst())
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private companion object {
        const val TEST_DB = "migration_19_20_test.db"
    }
}
