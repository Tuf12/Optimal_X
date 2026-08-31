package com.example.optimalx.ui.editor.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmMonoFamily

/**
 * Live-preview mapping: markdown source stays canonical, the TextField
 * shows rendered text without `#` / `**` / list markers.
 *
 * Selection and toolbar offsets stay in source space ([OffsetMapping]).
 */
object NoteMarkdownVisual {

    fun transform(source: String): TransformedText {
        if (source.isEmpty()) {
            return TransformedText(AnnotatedString(""), OffsetMapping.Identity)
        }
        val builder = VisualBuilder(source)
        builder.scanDocument()
        return builder.toTransformedText()
    }
}

private class VisualBuilder(private val source: String) {
    private val visual = StringBuilder()
    private val spanStyles = mutableListOf<AnnotatedString.Range<SpanStyle>>()
    private val origToVis = IntArray(source.length + 1)
    private val visToOrig = ArrayList<Int>(source.length + 1)
    private var vis = 0
    private var inFence = false

    fun scanDocument() {
        var i = 0
        while (i < source.length) {
            val lineEnd = source.indexOf('\n', i).let { if (it == -1) source.length else it }
            val line = source.substring(i, lineEnd)
            if (NoteMarkdownVisualFence.isFence(line)) {
                inFence = !inFence
                hideRange(i, lineEnd)
                if (lineEnd < source.length) {
                    showChar(lineEnd, '\n')
                    i = lineEnd + 1
                } else {
                    hideRange(lineEnd, lineEnd)
                    i = lineEnd
                }
                continue
            }
            if (inFence) {
                showRange(i, lineEnd, SpanStyle(fontFamily = DmMonoFamily, fontSize = 14.sp))
                if (lineEnd < source.length) {
                    showChar(lineEnd, '\n')
                    i = lineEnd + 1
                } else {
                    i = lineEnd
                }
                continue
            }
            i = emitBlockLine(i, lineEnd, line)
        }
        origToVis[source.length] = vis
        visToOrig.add(source.length)
    }

    fun toTransformedText(): TransformedText {
        val annotated = AnnotatedString(
            text = visual.toString(),
            spanStyles = spanStyles,
        )
        val visToOrigArr = visToOrig.toIntArray()
        return TransformedText(
            annotated,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    origToVis[offset.coerceIn(0, origToVis.lastIndex)]

                override fun transformedToOriginal(offset: Int): Int =
                    visToOrigArr[offset.coerceIn(0, visToOrigArr.lastIndex)]
            },
        )
    }

    private fun emitBlockLine(start: Int, lineEnd: Int, line: String): Int {
        headingPrefixMatch(line)?.let { match ->
            hideRange(start, start + match.prefixLength)
            val headingStyle = headingStyle(match.level)
            emitInline(start + match.prefixLength, lineEnd, headingStyle)
            return finishLine(lineEnd)
        }
        bulletPrefixMatch(line)?.let { match ->
            hideRange(start, start + match.prefixLength)
            emitVisualOnly('•', start + match.prefixLength)
            emitVisualOnly(' ', start + match.prefixLength)
            emitInline(start + match.prefixLength, lineEnd, null)
            return finishLine(lineEnd)
        }
        numberedPrefixMatch(line)?.let { match ->
            hideRange(start, start + match.prefixLength)
            match.label.forEach { ch -> emitVisualOnly(ch, start + match.prefixLength) }
            emitVisualOnly(' ', start + match.prefixLength)
            emitInline(start + match.prefixLength, lineEnd, null)
            return finishLine(lineEnd)
        }
        emitInline(start, lineEnd, null)
        return finishLine(lineEnd)
    }

    private fun finishLine(lineEnd: Int): Int {
        if (lineEnd < source.length) {
            showChar(lineEnd, '\n')
            return lineEnd + 1
        }
        return lineEnd
    }

    private fun emitInline(from: Int, to: Int, extra: SpanStyle?) {
        var i = from
        while (i < to) {
            val link = matchLink(i, to)
            if (link != null) {
                hideRange(i, link.labelStart)
                emitStyled(link.labelStart, link.labelEnd, linkStyle(extra))
                hideRange(link.labelEnd, link.end)
                i = link.end
                continue
            }
            val wrap = matchWrap(i, to)
            if (wrap != null) {
                hideRange(i, wrap.innerStart)
                emitStyled(wrap.innerStart, wrap.innerEnd, wrapStyle(wrap.marker, extra))
                hideRange(wrap.innerEnd, wrap.end)
                i = wrap.end
                continue
            }
            showChar(i, source[i], extra)
            i++
        }
    }

    private fun emitStyled(from: Int, to: Int, style: SpanStyle) {
        val visStart = vis
        showRange(from, to, null)
        spanStyles.add(AnnotatedString.Range(style, visStart, vis))
    }

    private fun showRange(from: Int, to: Int, style: SpanStyle?) {
        val visStart = vis
        for (idx in from until to) {
            showChar(idx, source[idx])
        }
        if (style != null && visStart < vis) {
            spanStyles.add(AnnotatedString.Range(style, visStart, vis))
        }
    }

    private fun showChar(origIndex: Int, ch: Char, extra: SpanStyle? = null) {
        origToVis[origIndex] = vis
        visToOrig.add(origIndex)
        val visStart = vis
        visual.append(ch)
        vis++
        if (extra != null) {
            spanStyles.add(AnnotatedString.Range(extra, visStart, vis))
        }
    }

    private fun emitVisualOnly(ch: Char, mapsToOrig: Int) {
        visToOrig.add(mapsToOrig)
        visual.append(ch)
        vis++
    }

    private fun hideRange(from: Int, to: Int) {
        for (idx in from until to) {
            origToVis[idx] = vis
        }
    }

    private fun matchWrap(from: Int, to: Int): WrapMatch? {
        val rest = source.substring(from, to)
        for (marker in WRAP_MARKERS) {
            if (!rest.startsWith(marker)) continue
            val close = rest.indexOf(marker, startIndex = marker.length)
            if (close <= marker.length - 1) continue
            val innerLen = close - marker.length
            if (innerLen <= 0) continue
            return WrapMatch(
                marker = marker,
                innerStart = from + marker.length,
                innerEnd = from + close,
                end = from + close + marker.length,
            )
        }
        return null
    }

    private fun matchLink(from: Int, to: Int): LinkMatch? {
        if (source[from] != '[') return null
        val closeLabel = source.indexOf(']', from + 1).takeIf { it in (from + 1) until to } ?: return null
        if (closeLabel + 1 >= to || source[closeLabel + 1] != '(') return null
        val closeUrl = source.indexOf(')', closeLabel + 2).takeIf { it in (closeLabel + 2) until to } ?: return null
        return LinkMatch(
            labelStart = from + 1,
            labelEnd = closeLabel,
            end = closeUrl + 1,
        )
    }

    private fun headingPrefixMatch(line: String): HeadingMatch? {
        val match = HEADING_PREFIX.find(line) ?: return null
        return HeadingMatch(level = match.groupValues[1].length, prefixLength = match.value.length)
    }

    private fun bulletPrefixMatch(line: String): PrefixMatch? {
        val match = BULLET_PREFIX.find(line) ?: return null
        return PrefixMatch(prefixLength = match.value.length)
    }

    private fun numberedPrefixMatch(line: String): NumberedMatch? {
        val match = NUMBERED_PREFIX.find(line) ?: return null
        return NumberedMatch(prefixLength = match.value.length, label = match.groupValues[2] + ".")
    }

    private data class HeadingMatch(val level: Int, val prefixLength: Int)
    private data class PrefixMatch(val prefixLength: Int)
    private data class NumberedMatch(val prefixLength: Int, val label: String)
    private data class WrapMatch(val marker: String, val innerStart: Int, val innerEnd: Int, val end: Int)
    private data class LinkMatch(val labelStart: Int, val labelEnd: Int, val end: Int)

    companion object {
        private val WRAP_MARKERS = listOf("**", "~~", "`", "*")
        private val HEADING_PREFIX = Regex("^(#{1,3})\\s+")
        private val BULLET_PREFIX = Regex("^(\\s*)([-*+])\\s+")
        private val NUMBERED_PREFIX = Regex("^(\\s*)(\\d+)\\.\\s+")

        private fun headingStyle(level: Int): SpanStyle = when (level) {
            1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
            2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 19.sp)
            else -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }

        private fun wrapStyle(marker: String, extra: SpanStyle?): SpanStyle {
            val wrap = when (marker) {
                "**" -> SpanStyle(fontWeight = FontWeight.Bold)
                "*" -> SpanStyle(fontStyle = FontStyle.Italic)
                "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                "`" -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                else -> SpanStyle()
            }
            return extra?.merge(wrap) ?: wrap
        }

        private fun linkStyle(extra: SpanStyle?): SpanStyle {
            val link = SpanStyle(textDecoration = TextDecoration.Underline)
            return extra?.merge(link) ?: link
        }
    }
}

private object NoteMarkdownVisualFence {
    fun isFence(line: String): Boolean = line.trimStart().startsWith("```")
}
