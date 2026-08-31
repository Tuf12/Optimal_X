package com.example.optimalx.data.semantic

/**
 * Splits content into embeddable chunks (headings → paragraphs → size fallback with overlap).
 */
object ContentSegmentation {

    /** Target size per chunk before embedding (~512 tokens). */
    const val CHUNK_EMBED_TARGET_CHARS = 1_800
    const val CHUNK_OVERLAP_CHARS = 200
    const val FILE_LINES_PER_WINDOW = 120
    const val FILE_CHARS_PER_WINDOW = 2_500

    data class TextSegment(
        val text: String,
        val startLine: Int,
        val endLine: Int,
        val anchor: String,
        val chunkType: String,
    )

    fun splitContentSegments(text: String): List<TextSegment> {
        if (text.isBlank()) return emptyList()
        val sections = splitByHeadings(text)
        val rawSegments = mutableListOf<Pair<String, String>>()
        for ((heading, body) in sections) {
            val chunkType = if (heading.isNotBlank()) "heading" else "paragraph"
            val paragraphs = splitParagraphs(body)
            if (paragraphs.isEmpty() && heading.isNotBlank()) {
                rawSegments += heading to chunkType
                continue
            }
            for (para in paragraphs) {
                val combined = if (heading.isNotBlank()) "$heading\n\n$para" else para
                rawSegments += combined to chunkType
            }
        }
        val sized = mutableListOf<Pair<String, String>>()
        for ((segment, type) in rawSegments) {
            if (segment.length <= CHUNK_EMBED_TARGET_CHARS) {
                sized += segment to type
            } else {
                splitByCharWindow(segment).forEach { window ->
                    sized += window to type
                }
            }
        }
        return applyOverlap(mapRawSegmentsToLineRanges(text, sized.map { it.first }, sized.map { it.second }))
    }

    fun splitFileLineWindows(text: String): List<TextSegment> {
        val lines = text.lines()
        if (lines.isEmpty()) return emptyList()

        val segments = mutableListOf<TextSegment>()
        var startIdx = 0
        while (startIdx < lines.size) {
            var endIdx = (startIdx + FILE_LINES_PER_WINDOW - 1).coerceAtMost(lines.lastIndex)
            var charCount = 0
            for (i in startIdx..endIdx) charCount += lines[i].length + 1
            while (endIdx < lines.lastIndex && charCount < FILE_CHARS_PER_WINDOW) {
                endIdx += 1
                charCount += lines[endIdx].length + 1
            }
            val slice = lines.subList(startIdx, endIdx + 1).joinToString("\n")
            segments += TextSegment(
                text = slice,
                startLine = startIdx + 1,
                endLine = endIdx + 1,
                anchor = "lines ${startIdx + 1}–${endIdx + 1}",
                chunkType = "line_window",
            )
            startIdx = endIdx + 1
        }
        return segments
    }

    fun splitConversationBatches(threadText: String, linesPerBatch: Int = 12): List<TextSegment> {
        val lines = threadText.lines()
        if (lines.isEmpty()) return emptyList()
        val segments = mutableListOf<TextSegment>()
        var startIdx = 0
        while (startIdx < lines.size) {
            val endIdx = (startIdx + linesPerBatch - 1).coerceAtMost(lines.lastIndex)
            val slice = lines.subList(startIdx, endIdx + 1).joinToString("\n")
            segments += TextSegment(
                text = slice,
                startLine = startIdx + 1,
                endLine = endIdx + 1,
                anchor = "messages lines ${startIdx + 1}–${endIdx + 1}",
                chunkType = "thread_batch",
            )
            startIdx = endIdx + 1
        }
        return segments
    }

    fun extractLineRange(text: String, startLine: Int, endLine: Int): String {
        val lines = text.lines()
        if (lines.isEmpty()) return ""
        val start = (startLine - 1).coerceIn(0, lines.lastIndex)
        val end = (endLine - 1).coerceIn(start, lines.lastIndex)
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private fun splitByHeadings(text: String): List<Pair<String, String>> {
        val headingPattern = Regex("(?m)^(#{1,3}\\s+.+)$")
        val matches = headingPattern.findAll(text).toList()
        if (matches.isEmpty()) return listOf("" to text.trim())

        val sections = mutableListOf<Pair<String, String>>()
        var lastEnd = 0
        var lastHeading = ""
        matches.forEach { match ->
            if (match.range.first > lastEnd) {
                val before = text.substring(lastEnd, match.range.first).trim()
                if (before.isNotEmpty()) sections += lastHeading to before
            }
            lastHeading = match.value.trim()
            lastEnd = match.range.last + 1
        }
        val tail = text.substring(lastEnd).trim()
        if (tail.isNotEmpty() || lastHeading.isNotBlank()) {
            sections += lastHeading to tail
        }
        return sections.ifEmpty { listOf("" to text.trim()) }
    }

    private fun splitParagraphs(text: String): List<String> {
        val paragraphs = text.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return listOf(text.trim()).filter { it.isNotEmpty() }

        val segments = mutableListOf<String>()
        val current = StringBuilder()
        for (para in paragraphs) {
            val nextLen = current.length + para.length + if (current.isEmpty()) 0 else 2
            if (current.isNotEmpty() && nextLen > CHUNK_EMBED_TARGET_CHARS) {
                segments.add(current.toString().trim())
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(para)
        }
        if (current.isNotEmpty()) segments.add(current.toString().trim())
        return segments.ifEmpty { listOf(text.take(CHUNK_EMBED_TARGET_CHARS)) }
    }

    private fun splitByCharWindow(text: String): List<String> {
        val windows = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = (start + CHUNK_EMBED_TARGET_CHARS).coerceAtMost(text.length)
            windows += text.substring(start, end)
            if (end >= text.length) break
            start = (end - CHUNK_OVERLAP_CHARS).coerceAtLeast(start + 1)
        }
        return windows
    }

    private fun mapRawSegmentsToLineRanges(
        fullText: String,
        rawSegments: List<String>,
        chunkTypes: List<String>,
    ): List<TextSegment> {
        var searchFrom = 0
        return rawSegments.mapIndexed { index, segment ->
            val idx = fullText.indexOf(segment, searchFrom).coerceAtLeast(searchFrom)
            searchFrom = if (idx >= 0) idx + segment.length else searchFrom + segment.length
            val startLine = if (idx >= 0) fullText.take(idx).count { it == '\n' } + 1 else 1
            val endLine = startLine + segment.count { it == '\n' }
            TextSegment(
                text = segment,
                startLine = startLine,
                endLine = endLine,
                anchor = "lines $startLine–$endLine",
                chunkType = chunkTypes.getOrElse(index) { "paragraph" },
            )
        }
    }

    private fun applyOverlap(segments: List<TextSegment>): List<TextSegment> {
        if (segments.size <= 1) return segments
        return segments.mapIndexed { index, segment ->
            if (index == 0) return@mapIndexed segment
            val prev = segments[index - 1].text
            val overlap = prev.takeLast(CHUNK_OVERLAP_CHARS).trim()
            if (overlap.isBlank()) segment
            else segment.copy(text = "$overlap\n\n${segment.text}")
        }
    }
}
