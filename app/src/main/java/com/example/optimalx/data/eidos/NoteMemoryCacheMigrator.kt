package com.example.optimalx.data.eidos

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.optimalx.data.db.SystemFolderNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One-time v25 migration: copy legacy parent-level memory cache map entries into
 * per-note [Memory] bullets when empty.
 */
object NoteMemoryCacheMigrator {

    private val json = Json { ignoreUnknownKeys = true }

    fun migrate(db: SupportSQLiteDatabase) {
        db.query(
            """
            SELECT n.content AS cache_content
            FROM notes n
            INNER JOIN subfolders s ON s.id = n.subfolderId
            WHERE s.isSystemSubfolder = 1
              AND s.deletedAt IS NULL
              AND (s.name = ? OR s.name = ?)
            """.trimIndent(),
            arrayOf(
                SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER,
                "__memory_cache__",
            ),
        ).use { cursor ->
            val contentIndex = cursor.getColumnIndexOrThrow("cache_content")
            while (cursor.moveToNext()) {
                val cacheContent = cursor.getString(contentIndex).orEmpty()
                migrateCacheMap(db, cacheContent)
            }
        }
    }

    private fun migrateCacheMap(db: SupportSQLiteDatabase, cacheContent: String) {
        val cacheMap = decodeMemoryCacheMap(cacheContent)
        if (cacheMap.isEmpty()) return
        val now = System.currentTimeMillis()

        cacheMap.forEach { (targetSubfolderId, rulesetText) ->
            val trimmed = rulesetText.trim()
            if (trimmed.isBlank()) return@forEach

            db.query(
                """
                SELECT summary
                FROM notes
                WHERE subfolderId = ?
                  AND deletedAt IS NULL
                LIMIT 1
                """.trimIndent(),
                arrayOf(targetSubfolderId),
            ).use { noteCursor ->
                if (!noteCursor.moveToFirst()) return@forEach
                val summaryIndex = noteCursor.getColumnIndexOrThrow("summary")
                val existingSummary = noteCursor.getString(summaryIndex)
                val sections = NoteSummaryCodec.parse(existingSummary)
                if (sections.memoryBullets.isNotEmpty()) return@forEach

                val migrated = NoteSummaryCodec.setMemoryBullets(sections, trimmed)
                if (migrated !is NoteSummaryEditResult.Success) return@forEach
                val formatted = migrated.formatted.ifBlank { null }
                db.execSQL(
                    "UPDATE notes SET summary = ?, summaryUpdatedAt = ? WHERE subfolderId = ?",
                    arrayOf<Any?>(formatted, now, targetSubfolderId),
                )
            }
        }
    }

    internal fun decodeMemoryCacheMap(content: String): Map<Long, String> {
        if (content.isBlank()) return emptyMap()
        val parsed = runCatching { json.parseToJsonElement(content) }.getOrNull() ?: return emptyMap()
        val obj = parsed as? JsonObject ?: return emptyMap()
        val out = linkedMapOf<Long, String>()
        obj.forEach { (key, value) ->
            val id = key.toLongOrNull() ?: return@forEach
            val text = (value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (text.isNotBlank()) out[id] = text
        }
        return out
    }
}
