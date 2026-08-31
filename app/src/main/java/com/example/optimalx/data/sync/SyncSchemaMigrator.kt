package com.example.optimalx.data.sync

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.optimalx.data.db.SystemFolderNames
import java.util.UUID

/**
 * Room v27 → v28: sync columns (globalId, originDeviceId, contentHash, targetPlatform).
 */
object SyncSchemaMigrator {

  private data class TableSyncConfig(
      val table: String,
      val extraAlters: List<String> = emptyList(),
      val contentHashFromColumn: String? = null,
  )

  private val SYNC_TABLES = listOf(
      TableSyncConfig("parent_folders"),
      TableSyncConfig("subfolders", extraAlters = listOf(
          "ALTER TABLE subfolders ADD COLUMN targetPlatform TEXT NOT NULL DEFAULT 'mobile'",
      )),
      TableSyncConfig("notes", contentHashFromColumn = "content"),
      TableSyncConfig("file_references"),
      TableSyncConfig("home_pins"),
      TableSyncConfig("conversations"),
      TableSyncConfig("chat_messages"),
      TableSyncConfig("custom_panel_assignments"),
      TableSyncConfig("panel_state", contentHashFromColumn = "stateJson"),
      TableSyncConfig("content_checkpoints"),
      TableSyncConfig("content_patches"),
      TableSyncConfig("pending_change_sets"),
      TableSyncConfig("pending_change_items"),
  )

  fun migrate(db: SupportSQLiteDatabase) {
      SYNC_TABLES.forEach { config ->
          addGlobalColumns(db, config.table, config.extraAlters)
      }
      backfillSystemParentGlobalIds(db)
      backfillEmptyGlobalIds(db)
      SYNC_TABLES.forEach { config ->
          config.contentHashFromColumn?.let { column ->
              backfillContentHash(db, config.table, column)
          }
          createGlobalIdIndex(db, config.table)
      }
      backfillWorkshopTargetPlatform(db)
  }

  private fun addGlobalColumns(
      db: SupportSQLiteDatabase,
      table: String,
      extraAlters: List<String>,
  ) {
      db.execSQL("ALTER TABLE $table ADD COLUMN globalId TEXT NOT NULL DEFAULT ''")
      db.execSQL("ALTER TABLE $table ADD COLUMN originDeviceId TEXT")
      if (table == "notes") {
          db.execSQL("ALTER TABLE notes ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
      }
      if (table == "panel_state") {
          db.execSQL("ALTER TABLE panel_state ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
      }
      extraAlters.forEach { sql -> db.execSQL(sql) }
  }

  private fun backfillSystemParentGlobalIds(db: SupportSQLiteDatabase) {
      listOf(
          SystemFolderNames.EIDOS_JOURNAL,
          SystemFolderNames.EIDOS_LOG,
          SystemFolderNames.EIDOS_CHATS,
          SystemFolderNames.EIDOS_DAILY,
          SystemFolderNames.EIDOS_MEMORY,
          SystemFolderNames.EIDOS_REASONING,
          SystemFolderNames.QUICK_NOTES,
          SystemFolderNames.PANEL_WORKSHOP,
      ).forEach { name ->
          val globalId = SyncGlobalIds.systemParentGlobalId(name) ?: return@forEach
          db.execSQL(
              """
              UPDATE parent_folders
              SET globalId = ?
              WHERE name = ? AND isSystemFolder = 1
              """.trimIndent(),
              arrayOf(globalId, name),
          )
      }
  }

  private fun backfillEmptyGlobalIds(db: SupportSQLiteDatabase) {
      SYNC_TABLES.forEach { config ->
          db.query(
              "SELECT id FROM ${config.table} WHERE globalId = '' OR globalId IS NULL",
          ).use { cursor ->
              while (cursor.moveToNext()) {
                  val id = cursor.getLong(0)
                  db.execSQL(
                      "UPDATE ${config.table} SET globalId = ? WHERE id = ?",
                      arrayOf<Any>(UUID.randomUUID().toString(), id),
                  )
              }
          }
      }
  }

  private fun backfillContentHash(
      db: SupportSQLiteDatabase,
      table: String,
      contentColumn: String,
  ) {
      db.query("SELECT id, $contentColumn FROM $table").use { cursor ->
          val idIndex = cursor.getColumnIndexOrThrow("id")
          val contentIndex = cursor.getColumnIndexOrThrow(contentColumn)
          while (cursor.moveToNext()) {
              val id = cursor.getLong(idIndex)
              val content = cursor.getString(contentIndex).orEmpty()
              val hash = when (table) {
                  "notes" -> SyncContentHash.noteContentHash(content)
                  "panel_state" -> SyncContentHash.panelStateContentHash(content)
                  else -> SyncContentHash.sha256Hex(content)
              }
              db.execSQL(
                  "UPDATE $table SET contentHash = ? WHERE id = ?",
                  arrayOf<Any>(hash, id),
              )
          }
      }
  }

  private fun backfillWorkshopTargetPlatform(db: SupportSQLiteDatabase) {
      db.execSQL(
          """
          UPDATE subfolders
          SET targetPlatform = 'mobile'
          WHERE parentFolderId IN (
              SELECT id FROM parent_folders
              WHERE name = ? AND isSystemFolder = 1
          )
          """.trimIndent(),
          arrayOf(SystemFolderNames.PANEL_WORKSHOP),
      )
  }

  private fun createGlobalIdIndex(db: SupportSQLiteDatabase, table: String) {
      db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS index_${table}_globalId ON $table(globalId)",
      )
  }
}
