package com.example.optimalx.data.eidos

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Parses and serializes timestamped entries in Eidos Daily and Long-Term Memory notes.
 * Format matches [RoomToolExecutor.appendEntry].
 */
object EidosSystemMemoryFormat {

    private val lineTimestampRegex = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})]\s*(.*)$""")
    private val timestampInputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val timestampOutputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val chunkSeparator = Regex("\n\\s*\n")

    data class Entry(
        val subfolderId: Long,
        val chunkIndex: Int,
        val subfolderName: String,
        val timestampLabel: String?,
        val content: String,
        val sortKey: Long,
        val rawChunk: String,
    )

    fun splitChunks(noteContent: String): List<String> =
        noteContent
            .split(chunkSeparator)
            .map { it.trim() }
            .filter { it.isNotBlank() }

    fun joinChunks(chunks: List<String>): String =
        chunks.joinToString(separator = "\n\n")

    fun parseEntries(
        subfolderId: Long,
        subfolderName: String,
        noteContent: String,
        fallbackUpdatedAt: Long,
    ): List<Entry> {
        if (noteContent.isBlank()) return emptyList()
        val dayStartMillis = parseSubfolderDayStartMillis(subfolderName)
        return splitChunks(noteContent).mapIndexed { index, chunk ->
            val firstLine = chunk.lineSequence().firstOrNull().orEmpty().trim()
            val tsMatch = lineTimestampRegex.find(firstLine)
            val parsedMillis = tsMatch?.groupValues
                ?.getOrNull(1)
                ?.let { raw -> runCatching { raw.toLocalDateTimeAtSystemZoneMillis() }.getOrNull() }
            val resolvedMillis = parsedMillis ?: dayStartMillis ?: fallbackUpdatedAt
            val resolvedTimestampLabel = parsedMillis?.let {
                timestampOutputFormatter.format(
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                )
            }
            val cleanedContent = if (tsMatch != null && tsMatch.groupValues.size >= 3) {
                val firstLineWithoutTimestamp = tsMatch.groupValues[2].trim()
                val remainder = chunk.lineSequence().drop(1).joinToString("\n")
                listOf(firstLineWithoutTimestamp, remainder)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            } else {
                chunk
            }
            Entry(
                subfolderId = subfolderId,
                chunkIndex = index,
                subfolderName = subfolderName,
                timestampLabel = resolvedTimestampLabel,
                content = cleanedContent,
                sortKey = resolvedMillis,
                rawChunk = chunk,
            )
        }
    }

    private fun parseSubfolderDayStartMillis(subfolderName: String): Long? =
        runCatching {
            val day = java.time.LocalDate.parse(subfolderName)
            day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()

    private fun String.toLocalDateTimeAtSystemZoneMillis(): Long {
        val parsed = java.time.LocalDateTime.parse(this, timestampInputFormatter)
        return parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
