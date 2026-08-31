package com.example.optimalx.data.quicknotes

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One logical line or block stored under `[HH:MM] …` in a Quick Notes daily note. */
data class QuickNoteEntry(
    val timeLabel: String,
    val body: String,
)

object QuickNotesFormat {

    private val isoDate = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val entryStart = Regex("""^\[(\d{2}:\d{2})]\s*(.*)$""")

    fun isIsoDateFolderName(name: String): Boolean {
        if (!isoDate.matches(name)) return false
        return runCatching { LocalDate.parse(name) }.isSuccess
    }

    fun timeLabelForMillis(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm")
            .withZone(zone)
            .format(Instant.ofEpochMilli(epochMillis))

    fun parseEntries(raw: String): List<QuickNoteEntry> {
        val text = raw.trimEnd('\n')
        if (text.isBlank()) return emptyList()
        val lines = text.lines()
        val out = mutableListOf<QuickNoteEntry>()
        var i = 0
        while (i < lines.size) {
            val m = entryStart.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val time = m.groupValues[1]
            val firstLineBody = m.groupValues[2]
            val bodyLines = mutableListOf<String>()
            if (firstLineBody.isNotEmpty()) bodyLines.add(firstLineBody)
            i++
            while (i < lines.size && !lines[i].matches(entryStart)) {
                bodyLines.add(lines[i])
                i++
            }
            out.add(QuickNoteEntry(time, bodyLines.joinToString("\n").trimEnd()))
        }
        return out
    }

    fun serializeEntries(entries: List<QuickNoteEntry>): String =
        entries.joinToString("\n\n") { e ->
            val b = e.body.trim()
            "[${e.timeLabel}] $b".trimEnd()
        }

    fun appendBlock(existing: String, timeLabel: String, body: String): String {
        val trimmedBody = body.trim()
        val block = "[$timeLabel] $trimmedBody"
        val base = existing.trimEnd()
        return if (base.isEmpty()) block else "$base\n\n$block"
    }
}
