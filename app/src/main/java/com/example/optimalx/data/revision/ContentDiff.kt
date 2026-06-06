package com.example.optimalx.data.revision

import java.security.MessageDigest

/**
 * Line-based unified-diff utility for the DIFF_REVIEW pipeline.
 *
 * Workshop files in v1 are well under a few thousand lines, so an O(N·M) LCS
 * computation is acceptable. For safety on pathological inputs we fall back to
 * a single "replace all" hunk above [MAX_DIFF_LINES] on either side.
 *
 * No third-party diff dependency by design — the plan calls for an in-house
 * implementation so the patch format and behavior stay stable across the
 * tool, service, and review-UI layers.
 */
object ContentDiff {

    /** Above this on either side, fall back to a whole-file replace hunk. */
    const val MAX_DIFF_LINES: Int = 8000

    /** Default unified-diff context window (lines of unchanged content per hunk). */
    const val DEFAULT_CONTEXT_LINES: Int = 3

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a unified diff (no file headers, one or more `@@` hunks). Empty
     * string when [before] and [after] are byte-equal.
     */
    fun unifiedDiff(
        before: String,
        after: String,
        contextLines: Int = DEFAULT_CONTEXT_LINES,
    ): String {
        if (before == after) return ""
        val a = splitLines(before)
        val b = splitLines(after)

        if (a.size > MAX_DIFF_LINES || b.size > MAX_DIFF_LINES) {
            return wholeFileReplaceHunk(a, b)
        }

        val ops = diffOps(a, b)
        if (ops.all { it is EditOp.Keep }) return ""
        return formatHunks(ops, contextLines)
    }

    /**
     * Apply [unifiedDiff] (produced by [unifiedDiff]) to [before]. Throws
     * [IllegalArgumentException] when the patch does not cleanly apply — context
     * lines must match exactly.
     */
    fun applyUnifiedDiff(before: String, unifiedDiff: String): String {
        if (unifiedDiff.isEmpty()) return before
        val srcLines = splitLines(before).toMutableList()
        val out = mutableListOf<String>()
        var srcIndex = 0

        val hunks = parseHunks(unifiedDiff)
        for (hunk in hunks) {
            // `oldStart == 0` is the unified-diff convention for "insert before line 1"
            // (the file is empty at this hunk's old range); treat that as position 0.
            val targetSrcIndex = if (hunk.oldStart == 0) 0 else hunk.oldStart - 1
            require(targetSrcIndex in 0..srcLines.size) {
                "Hunk old-start ${hunk.oldStart} out of range (file has ${srcLines.size} lines)"
            }
            while (srcIndex < targetSrcIndex) {
                out.add(srcLines[srcIndex])
                srcIndex++
            }
            for (line in hunk.lines) {
                when (line.tag) {
                    ' ' -> {
                        require(srcIndex < srcLines.size && srcLines[srcIndex] == line.text) {
                            "Context mismatch at line ${srcIndex + 1}: expected '${line.text}', got '${srcLines.getOrNull(srcIndex)}'"
                        }
                        out.add(srcLines[srcIndex])
                        srcIndex++
                    }
                    '-' -> {
                        require(srcIndex < srcLines.size && srcLines[srcIndex] == line.text) {
                            "Deletion mismatch at line ${srcIndex + 1}: expected '${line.text}', got '${srcLines.getOrNull(srcIndex)}'"
                        }
                        srcIndex++
                    }
                    '+' -> out.add(line.text)
                }
            }
        }
        while (srcIndex < srcLines.size) {
            out.add(srcLines[srcIndex])
            srcIndex++
        }
        // When applying onto an empty source we have no trailing-newline signal
        // from `before`, so fall back to the standard text-file convention
        // (content ends with a newline). v1 limitation: we don't emit
        // `\ No newline at end of file` markers, so trailing-newline state is
        // only preserved when both sides agree.
        val effectiveTrailingNewline = when {
            out.isEmpty() -> false
            before.isEmpty() -> true
            else -> before.endsWith('\n')
        }
        return joinLines(out, effectiveTrailingNewline)
    }

    /** SHA-256 hex of [content], lowercase, no separators. */
    fun sha256Hex(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** A window of source text around a target line, with explicit line numbers. */
    data class FileSnippet(
        val startLine: Int,
        val endLine: Int,
        val text: String,
    ) {
        /** Pretty form used by Eidos failure messages: `23 |  the line text`. */
        fun formatWithLineNumbers(): String = buildString {
            val lines = text.split('\n')
            val width = endLine.toString().length
            lines.forEachIndexed { idx, line ->
                if (idx > 0) appendLine()
                val n = startLine + idx
                append(n.toString().padStart(width))
                append(" | ")
                append(line)
            }
        }
    }

    /**
     * Returns a [contextLines]-wide window around the first occurrence of [needle]
     * in [content]. If [needle] is not found, falls back to the start of the file
     * so the LLM still gets *some* current state to re-anchor against.
     */
    fun snippetAround(
        content: String,
        needle: String,
        contextLines: Int = 5,
    ): FileSnippet {
        val lines = splitLines(content)
        if (lines.isEmpty()) return FileSnippet(1, 1, "")
        val targetLineIdx = findLineContaining(lines, needle).coerceAtLeast(0)
        val startIdx = (targetLineIdx - contextLines).coerceAtLeast(0)
        val endIdx = (targetLineIdx + contextLines).coerceAtMost(lines.size - 1)
        val slice = lines.subList(startIdx, endIdx + 1).joinToString("\n")
        return FileSnippet(
            startLine = startIdx + 1,
            endLine = endIdx + 1,
            text = slice,
        )
    }

    // ── Diff core (LCS DP) ────────────────────────────────────────────────────

    private sealed interface EditOp {
        data class Keep(val text: String) : EditOp
        data class Insert(val text: String) : EditOp
        data class Delete(val text: String) : EditOp
    }

    private fun diffOps(a: List<String>, b: List<String>): List<EditOp> {
        val n = a.size
        val m = b.size
        if (n == 0) return b.map { EditOp.Insert(it) }
        if (m == 0) return a.map { EditOp.Delete(it) }

        // dp[i][j] = LCS length of a[0..i) and b[0..j)
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            val ai = a[i - 1]
            val row = dp[i]
            val prev = dp[i - 1]
            for (j in 1..m) {
                row[j] = if (ai == b[j - 1]) prev[j - 1] + 1
                else maxOf(prev[j], row[j - 1])
            }
        }

        val ops = ArrayDeque<EditOp>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when {
                a[i - 1] == b[j - 1] -> {
                    ops.addFirst(EditOp.Keep(a[i - 1]))
                    i--; j--
                }
                dp[i - 1][j] >= dp[i][j - 1] -> {
                    ops.addFirst(EditOp.Delete(a[i - 1]))
                    i--
                }
                else -> {
                    ops.addFirst(EditOp.Insert(b[j - 1]))
                    j--
                }
            }
        }
        while (i > 0) {
            ops.addFirst(EditOp.Delete(a[i - 1]))
            i--
        }
        while (j > 0) {
            ops.addFirst(EditOp.Insert(b[j - 1]))
            j--
        }
        return ops.toList()
    }

    // ── Unified diff format ───────────────────────────────────────────────────

    private fun formatHunks(ops: List<EditOp>, contextLines: Int): String {
        val hunks = collectHunkRanges(ops, contextLines)
        val sb = StringBuilder()
        var oldLine = 1
        var newLine = 1
        var opIdx = 0
        for (hunk in hunks) {
            // Advance counters through Keep ops before this hunk.
            while (opIdx < hunk.startOp) {
                val op = ops[opIdx]
                if (op is EditOp.Keep) { oldLine++; newLine++ }
                opIdx++
            }
            var oldCount = 0
            var newCount = 0
            val body = StringBuilder()
            val hunkOldStart = oldLine
            val hunkNewStart = newLine
            for (k in hunk.startOp until hunk.endOp) {
                when (val op = ops[k]) {
                    is EditOp.Keep -> {
                        body.append(' ').append(op.text).append('\n')
                        oldCount++; newCount++; oldLine++; newLine++
                    }
                    is EditOp.Delete -> {
                        body.append('-').append(op.text).append('\n')
                        oldCount++; oldLine++
                    }
                    is EditOp.Insert -> {
                        body.append('+').append(op.text).append('\n')
                        newCount++; newLine++
                    }
                }
            }
            opIdx = hunk.endOp
            sb.append("@@ -")
                .append(formatRange(hunkOldStart, oldCount))
                .append(" +")
                .append(formatRange(hunkNewStart, newCount))
                .append(" @@\n")
                .append(body)
        }
        return sb.toString()
    }

    private fun formatRange(start: Int, count: Int): String =
        if (count == 1) "$start" else "${if (count == 0) start - 1 else start},$count"

    private data class HunkRange(val startOp: Int, val endOp: Int)

    /**
     * Walk [ops] grouping adjacent change runs (Insert/Delete) plus [contextLines]
     * of surrounding Keep lines into one hunk. Overlapping context windows merge.
     */
    private fun collectHunkRanges(ops: List<EditOp>, contextLines: Int): List<HunkRange> {
        val changeIndices = ops.indices.filter { ops[it] !is EditOp.Keep }
        if (changeIndices.isEmpty()) return emptyList()

        val ranges = mutableListOf<HunkRange>()
        var curStart = (changeIndices.first() - contextLines).coerceAtLeast(0)
        var curEnd = (changeIndices.first() + contextLines + 1).coerceAtMost(ops.size)
        for (i in 1 until changeIndices.size) {
            val ci = changeIndices[i]
            val newStart = (ci - contextLines).coerceAtLeast(0)
            val newEnd = (ci + contextLines + 1).coerceAtMost(ops.size)
            if (newStart <= curEnd) {
                curEnd = maxOf(curEnd, newEnd)
            } else {
                ranges.add(HunkRange(curStart, curEnd))
                curStart = newStart
                curEnd = newEnd
            }
        }
        ranges.add(HunkRange(curStart, curEnd))
        return ranges
    }

    private fun wholeFileReplaceHunk(a: List<String>, b: List<String>): String {
        val sb = StringBuilder()
        sb.append("@@ -")
            .append(formatRange(if (a.isEmpty()) 0 else 1, a.size))
            .append(" +")
            .append(formatRange(if (b.isEmpty()) 0 else 1, b.size))
            .append(" @@\n")
        for (line in a) sb.append('-').append(line).append('\n')
        for (line in b) sb.append('+').append(line).append('\n')
        return sb.toString()
    }

    // ── Unified diff parser (for applyUnifiedDiff) ────────────────────────────

    private data class HunkLine(val tag: Char, val text: String)
    private data class ParsedHunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<HunkLine>,
    )

    private fun parseHunks(unifiedDiff: String): List<ParsedHunk> {
        val lines = unifiedDiff.split('\n')
        val out = mutableListOf<ParsedHunk>()
        var i = 0
        while (i < lines.size) {
            val header = lines[i]
            if (!header.startsWith("@@")) {
                i++; continue
            }
            val match = HUNK_HEADER.find(header)
                ?: throw IllegalArgumentException("Malformed hunk header: $header")
            val oldStart = match.groupValues[1].toInt()
            val oldCount = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: 1
            val newStart = match.groupValues[3].toInt()
            val newCount = match.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }?.toInt() ?: 1
            i++
            val body = mutableListOf<HunkLine>()
            while (i < lines.size && !lines[i].startsWith("@@")) {
                val line = lines[i]
                if (line.isEmpty()) { i++; continue }
                val tag = line[0]
                if (tag != ' ' && tag != '+' && tag != '-' && tag != '\\') {
                    break
                }
                if (tag != '\\') {
                    body.add(HunkLine(tag, line.substring(1)))
                }
                i++
            }
            out.add(ParsedHunk(oldStart, oldCount, newStart, newCount, body))
        }
        return out
    }

    private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Split content into lines without including trailing empty token. */
    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val raw = text.split('\n')
        return if (raw.lastOrNull() == "" && text.endsWith('\n')) {
            raw.subList(0, raw.size - 1)
        } else raw
    }

    private fun joinLines(lines: List<String>, trailingNewline: Boolean): String {
        // Total deletion always collapses to empty regardless of the source's
        // trailing-newline state — re-adding a bare "\n" would surface as a
        // spurious one-character file in the editor.
        if (lines.isEmpty()) return ""
        val joined = lines.joinToString("\n")
        return if (trailingNewline) "$joined\n" else joined
    }

    private fun findLineContaining(lines: List<String>, needle: String): Int {
        if (needle.isEmpty()) return 0
        val firstNeedleLine = needle.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstNeedleLine.isEmpty()) return 0
        lines.forEachIndexed { idx, line ->
            if (line.contains(firstNeedleLine)) return idx
        }
        return 0
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
