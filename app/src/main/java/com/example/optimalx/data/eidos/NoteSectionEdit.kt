package com.example.optimalx.data.eidos

import com.example.optimalx.data.revision.ContentDiff
import com.example.optimalx.data.semantic.ContentSegmentation

/**
 * Shared logic for [edit_note_section]: line-range splice or unique string replace.
 */
object NoteSectionEdit {

    sealed interface Result {
        data class Ok(val content: String) : Result
        data class Error(val message: String) : Result
    }

    fun buildProposedContent(
        current: String,
        newContent: String,
        startLine: Int?,
        endLine: Int?,
        oldString: String?,
        expectedContent: String?,
    ): Result {
        val incoming = newContent
        val hasLineRange = startLine != null && endLine != null

        if (hasLineRange) {
            val start = startLine!!
            val end = endLine!!
            val existing = ContentSegmentation.extractLineRange(current, start, end)
            if (expectedContent != null && expectedContent != existing) {
                val snippet = ContentDiff.snippetAround(current, existing.take(40), contextLines = 5)
                return Result.Error(
                    buildString {
                        appendLine("Lines $start\u2013$end no longer match expectedContent (note may have changed).")
                        appendLine("Current note (lines ${snippet.startLine}\u2013${snippet.endLine}):")
                        append(snippet.formatWithLineNumbers())
                    },
                )
            }
            return spliceLineRange(current, start, end, incoming)
        }

        val old = oldString?.takeIf { it.isNotEmpty() }
            ?: return Result.Error(
                "Provide startLine+endLine (preferred after search_semantic) or oldString for string match.",
            )
        if (old == incoming) {
            return Result.Error("oldString and newContent are identical \u2014 nothing to change.")
        }

        val firstIndex = current.indexOf(old)
        if (firstIndex < 0) {
            val snippet = ContentDiff.snippetAround(current, old, contextLines = 5)
            return Result.Error(
                buildString {
                    appendLine("oldString not found in note.")
                    appendLine(
                        "Copy oldString from a search_semantic chunk_text hit (include surrounding lines), then retry.",
                    )
                    appendLine("Current note (lines ${snippet.startLine}\u2013${snippet.endLine}):")
                    append(snippet.formatWithLineNumbers())
                },
            )
        }
        val secondIndex = current.indexOf(old, firstIndex + 1)
        if (secondIndex >= 0) {
            val total = countOccurrences(current, old)
            val snippet = ContentDiff.snippetAround(current, old, contextLines = 5)
            return Result.Error(
                buildString {
                    appendLine("oldString matched $total occurrences \u2014 must be unique.")
                    appendLine(
                        "Add more surrounding context to oldString so it identifies one specific occurrence, then retry.",
                    )
                    appendLine("Current note (lines ${snippet.startLine}\u2013${snippet.endLine}):")
                    append(snippet.formatWithLineNumbers())
                },
            )
        }

        return Result.Ok(
            current.substring(0, firstIndex) + incoming + current.substring(firstIndex + old.length),
        )
    }

    private fun spliceLineRange(
        fullText: String,
        startLine: Int,
        endLine: Int,
        newContent: String,
    ): Result {
        val lines = fullText.lines()
        val lineCount = lines.size
        val start = startLine.coerceAtLeast(1)
        val end = endLine.coerceIn(start, lineCount.coerceAtLeast(1))
        if (start > lineCount) {
            return Result.Error(
                "startLine $startLine is beyond note end (note has $lineCount lines).",
            )
        }
        val before = lines.subList(0, start - 1)
        val after = if (end < lineCount) lines.subList(end, lineCount) else emptyList()
        val inserted = newContent.lines()
        return Result.Ok((before + inserted + after).joinToString("\n"))
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) break
            count += 1
            idx = found + needle.length
        }
        return count
    }
}
