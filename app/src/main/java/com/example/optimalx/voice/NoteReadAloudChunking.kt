package com.example.optimalx.voice

import android.speech.tts.TextToSpeech

/** Approximate characters spoken in ~10 seconds (~130 WPM prose ≈ 40 chars/sec). */
internal const val NOTE_READ_ALOUD_SKIP_CHARS: Int = 400

internal fun buildReadAloudChunkStarts(chunks: List<String>): IntArray {
    val starts = IntArray(chunks.size + 1)
    var acc = 0
    for (i in chunks.indices) {
        starts[i] = acc
        acc += chunks[i].length
    }
    starts[chunks.size] = acc
    return starts
}

internal fun chunkIndexForCharOffset(starts: IntArray, offset: Int): Int {
    if (starts.size <= 1) return 0
    val total = starts[starts.size - 1]
    if (total <= 0) return 0
    val coerced = offset.coerceIn(0, total - 1)
    for (i in starts.size - 2 downTo 0) {
        if (starts[i] <= coerced) return i
    }
    return 0
}

/**
 * Splits sanitized prose into chunks for sequential TTS (sentence boundaries, under engine limit).
 */
internal fun chunkNoteForReadAloud(sanitized: String, targetChunkChars: Int = 320): List<String> {
    val normalized = sanitized.replace(Regex("\\s+"), " ").trim()
    if (normalized.isEmpty()) return emptyList()
    val maxUtterance = TextToSpeech.getMaxSpeechInputLength() - 8

    fun flushHard(s: String): List<String> {
        if (s.length <= maxUtterance) return listOf(s)
        val parts = mutableListOf<String>()
        var rest = s
        while (rest.isNotEmpty()) {
            val take = maxUtterance.coerceAtMost(rest.length)
            parts.add(rest.take(take))
            rest = rest.drop(take)
        }
        return parts
    }

    val sentences = normalized.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    if (sentences.isEmpty()) return flushHard(normalized)

    val chunks = mutableListOf<String>()
    val buf = StringBuilder()
    for (sentence in sentences) {
        val projected = buf.length + if (buf.isEmpty()) sentence.length else 1 + sentence.length
        if (projected > targetChunkChars && buf.isNotEmpty()) {
            chunks.addAll(flushHard(buf.toString().trim()))
            buf.clear()
        }
        if (buf.isEmpty()) buf.append(sentence) else {
            buf.append(' ')
            buf.append(sentence)
        }
        while (buf.length > maxUtterance) {
            val piece = buf.take(maxUtterance).toString()
            chunks.add(piece)
            buf.delete(0, piece.length)
        }
    }
    if (buf.isNotEmpty()) {
        chunks.addAll(flushHard(buf.toString().trim()))
    }
    return chunks.filter { it.isNotBlank() }
}
