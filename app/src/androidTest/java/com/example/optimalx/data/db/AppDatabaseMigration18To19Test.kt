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
class AppDatabaseMigration18To19Test {

    @Test
    fun migration_18_19_creates_home_pins_table() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(TEST_DB)

        val callback = object : SupportSQLiteOpenHelper.Callback(18) {
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
            val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 18 && it.endVersion == 19 }
            migration.migrate(db)

            val cursor = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'home_pins'",
            )
            cursor.use {
                assertTrue(it.moveToFirst())
            }

            val indexCursor = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_home_pins_pinType_targetId'",
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
        const val TEST_DB = "migration_18_19_test.db"
    }
}
