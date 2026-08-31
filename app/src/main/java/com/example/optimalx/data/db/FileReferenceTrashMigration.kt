package com.example.optimalx.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

object FileReferenceTrashMigration {

    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE file_references ADD COLUMN deletedAt INTEGER DEFAULT NULL",
        )
    }
}
