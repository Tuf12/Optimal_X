package com.example.optimalx.data.eidos

/**
 * Two-section note summary stored in [com.example.optimalx.data.model.Note.summary]:
 *
 * ```
 * [Memory]
 * - curated bullet
 *
 * [Content]
 * auto-maintained body digest
 * ```
 */
data class NoteSummarySections(
    val memoryBullets: List<String> = emptyList(),
    val contentDigest: String = "",
) {
    val isEmpty: Boolean
        get() = memoryBullets.isEmpty() && contentDigest.isBlank()
}

sealed class NoteSummaryEditResult {
    data class Success(val sections: NoteSummarySections, val formatted: String) : NoteSummaryEditResult()
    data class Failure(val message: String) : NoteSummaryEditResult()
}

object NoteSummaryCodec {

    fun parse(raw: String?): NoteSummarySections {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return NoteSummarySections()

        if (!looksStructured(text)) {
            return NoteSummarySections(contentDigest = text)
        }

        val memoryBlock = extractSection(text, NoteSummaryPolicy.MEMORY_HEADER, NoteSummaryPolicy.CONTENT_HEADER)
        val contentBlock = extractSectionAfter(text, NoteSummaryPolicy.CONTENT_HEADER)

        return NoteSummarySections(
            memoryBullets = parseBullets(memoryBlock),
            contentDigest = contentBlock.trim(),
        )
    }

    fun format(sections: NoteSummarySections): String? {
        val normalized = normalizeSections(sections)
        if (normalized.isEmpty) return null
        return buildString {
            if (normalized.memoryBullets.isNotEmpty()) {
                appendLine(NoteSummaryPolicy.MEMORY_HEADER)
                normalized.memoryBullets.forEach { bullet ->
                    appendLine("- $bullet")
                }
            }
            if (normalized.contentDigest.isNotBlank()) {
                if (normalized.memoryBullets.isNotEmpty()) appendLine()
                appendLine(NoteSummaryPolicy.CONTENT_HEADER)
                append(normalized.contentDigest.trim())
            }
        }.trim().ifBlank { null }
    }

    fun format(memoryBullets: List<String>, contentDigest: String): String? =
        format(NoteSummarySections(memoryBullets = memoryBullets, contentDigest = contentDigest))

    /**
     * Converts legacy [Note.summary] + [Note.summaryChunksJson] into the two-section format.
     */
    fun migrateLegacyStoredSummary(summary: String?, summaryChunksJson: String?): String? {
        val summaryText = summary?.trim().orEmpty()
        val chunks = ContentSummaryChunksCodec.decode(summaryChunksJson)

        if (summaryText.isNotEmpty() && looksStructured(summaryText)) {
            return format(parse(summaryText))
        }

        val contentParts = mutableListOf<String>()
        if (summaryText.isNotEmpty()) {
            contentParts += summaryText
        }
        if (chunks.isNotEmpty()) {
            val sectionText = chunks.joinToString("\n\n") { chunk ->
                "[${chunk.anchor}]\n${chunk.text.trim()}"
            }
            contentParts += sectionText
        }

        if (contentParts.isEmpty()) return null

        return format(
            NoteSummarySections(
                memoryBullets = emptyList(),
                contentDigest = contentParts.joinToString("\n\n").trim(),
            ),
        )
    }

    fun withContentDigest(sections: NoteSummarySections, contentDigest: String): NoteSummaryEditResult {
        val trimmed = contentDigest.trim()
        if (trimmed.length > NoteSummaryPolicy.MAX_CONTENT_DIGEST_CHARS) {
            return NoteSummaryEditResult.Failure(
                "Content digest exceeds ${NoteSummaryPolicy.MAX_CONTENT_DIGEST_CHARS} characters.",
            )
        }
        val updated = normalizeSections(sections.copy(contentDigest = trimmed))
        val formatted = format(updated) ?: return NoteSummaryEditResult.Failure("Summary is empty.")
        return NoteSummaryEditResult.Success(updated, formatted)
    }

    fun appendMemoryBullet(sections: NoteSummarySections, item: String): NoteSummaryEditResult {
        val bullet = normalizeBulletText(item)
            ?: return NoteSummaryEditResult.Failure("Bullet text is required.")
        val current = normalizeSections(sections)
        if (current.memoryBullets.any { bulletsEquivalent(it, bullet) }) {
            return NoteSummaryEditResult.Failure("Duplicate memory bullet.")
        }
        if (current.memoryBullets.size >= NoteSummaryPolicy.MAX_MEMORY_BULLETS) {
            return NoteSummaryEditResult.Failure(
                "Memory bullet limit (${NoteSummaryPolicy.MAX_MEMORY_BULLETS}) reached.",
            )
        }
        val nextBullets = current.memoryBullets + bullet
        if (memoryCharCount(nextBullets) > NoteSummaryPolicy.MAX_MEMORY_CHARS) {
            return NoteSummaryEditResult.Failure(
                "Memory section exceeds ${NoteSummaryPolicy.MAX_MEMORY_CHARS} characters.",
            )
        }
        val updated = current.copy(memoryBullets = nextBullets)
        val formatted = format(updated) ?: return NoteSummaryEditResult.Failure("Summary is empty.")
        return NoteSummaryEditResult.Success(updated, formatted)
    }

    fun replaceMemoryBullet(
        sections: NoteSummarySections,
        match: String,
        item: String,
    ): NoteSummaryEditResult {
        val bullet = normalizeBulletText(item)
            ?: return NoteSummaryEditResult.Failure("Replacement bullet text is required.")
        val current = normalizeSections(sections)
        val index = resolveBulletIndex(current.memoryBullets, match)
            ?: return NoteSummaryEditResult.Failure("No memory bullet matched: $match")
        val next = current.memoryBullets.toMutableList()
        if (next.anyIndexed { i, existing -> i != index && bulletsEquivalent(existing, bullet) }) {
            return NoteSummaryEditResult.Failure("Duplicate memory bullet.")
        }
        next[index] = bullet
        if (memoryCharCount(next) > NoteSummaryPolicy.MAX_MEMORY_CHARS) {
            return NoteSummaryEditResult.Failure(
                "Memory section exceeds ${NoteSummaryPolicy.MAX_MEMORY_CHARS} characters.",
            )
        }
        val updated = current.copy(memoryBullets = next)
        val formatted = format(updated) ?: return NoteSummaryEditResult.Failure("Summary is empty.")
        return NoteSummaryEditResult.Success(updated, formatted)
    }

    fun removeMemoryBullet(sections: NoteSummarySections, match: String): NoteSummaryEditResult {
        val current = normalizeSections(sections)
        val index = resolveBulletIndex(current.memoryBullets, match)
            ?: return NoteSummaryEditResult.Failure("No memory bullet matched: $match")
        val next = current.memoryBullets.toMutableList().apply { removeAt(index) }
        val updated = current.copy(memoryBullets = next)
        val formatted = format(updated)
            ?: return NoteSummaryEditResult.Success(updated, "")
        return NoteSummaryEditResult.Success(updated, formatted)
    }

    fun setMemoryBullets(sections: NoteSummarySections, rawBullets: String): NoteSummaryEditResult {
        val parsed = parseBullets(rawBullets)
        val bullets = if (parsed.isNotEmpty()) {
            parsed
        } else {
            rawBullets.lines().mapNotNull { line ->
                normalizeBulletText(line.trim().removePrefix("- "))
            }
        }
        val normalized = normalizeSections(sections.copy(memoryBullets = bullets))
        if (normalized.memoryBullets.size > NoteSummaryPolicy.MAX_MEMORY_BULLETS) {
            return NoteSummaryEditResult.Failure(
                "Memory bullet limit (${NoteSummaryPolicy.MAX_MEMORY_BULLETS}) exceeded.",
            )
        }
        if (memoryCharCount(normalized.memoryBullets) > NoteSummaryPolicy.MAX_MEMORY_CHARS) {
            return NoteSummaryEditResult.Failure(
                "Memory section exceeds ${NoteSummaryPolicy.MAX_MEMORY_CHARS} characters.",
            )
        }
        val formatted = format(normalized)
            ?: return NoteSummaryEditResult.Success(normalized, "")
        return NoteSummaryEditResult.Success(normalized, formatted)
    }

    fun validateAndFormat(sections: NoteSummarySections): NoteSummaryEditResult {
        val bullets = sections.memoryBullets.mapNotNull { normalizeBulletText(it) }
        if (bullets.size > NoteSummaryPolicy.MAX_MEMORY_BULLETS) {
            return NoteSummaryEditResult.Failure(
                "Memory bullet limit (${NoteSummaryPolicy.MAX_MEMORY_BULLETS}) exceeded.",
            )
        }
        if (memoryCharCount(bullets) > NoteSummaryPolicy.MAX_MEMORY_CHARS) {
            return NoteSummaryEditResult.Failure(
                "Memory section exceeds ${NoteSummaryPolicy.MAX_MEMORY_CHARS} characters.",
            )
        }
        val digest = sections.contentDigest.trim()
        if (digest.length > NoteSummaryPolicy.MAX_CONTENT_DIGEST_CHARS) {
            return NoteSummaryEditResult.Failure(
                "Content digest exceeds ${NoteSummaryPolicy.MAX_CONTENT_DIGEST_CHARS} characters.",
            )
        }
        val normalized = NoteSummarySections(
            memoryBullets = bullets.distinctBy { it.lowercase() },
            contentDigest = digest,
        )
        if (normalized.isEmpty) {
            return NoteSummaryEditResult.Success(normalized, "")
        }
        val formatted = format(normalized)
            ?: return NoteSummaryEditResult.Success(normalized, "")
        return NoteSummaryEditResult.Success(normalized, formatted)
    }

    internal fun normalizeSections(sections: NoteSummarySections): NoteSummarySections {
        val bullets = sections.memoryBullets
            .mapNotNull { normalizeBulletText(it) }
            .distinctBy { it.lowercase() }
            .take(NoteSummaryPolicy.MAX_MEMORY_BULLETS)
        val digest = sections.contentDigest.trim().take(NoteSummaryPolicy.MAX_CONTENT_DIGEST_CHARS)
        return NoteSummarySections(memoryBullets = bullets, contentDigest = digest)
    }

    private fun looksStructured(text: String): Boolean {
        return text.contains(NoteSummaryPolicy.MEMORY_HEADER) ||
            text.contains(NoteSummaryPolicy.CONTENT_HEADER)
    }

    private fun extractSection(text: String, header: String, nextHeader: String): String {
        val start = text.indexOf(header)
        if (start < 0) return ""
        val bodyStart = start + header.length
        val next = text.indexOf(nextHeader, startIndex = bodyStart)
        val end = if (next >= 0) next else text.length
        return text.substring(bodyStart, end).trim()
    }

    private fun extractSectionAfter(text: String, header: String): String {
        val start = text.indexOf(header)
        if (start < 0) return ""
        return text.substring(start + header.length).trim()
    }

    private fun parseBullets(block: String): List<String> {
        if (block.isBlank()) return emptyList()
        return block.lines()
            .map { it.trim() }
            .mapNotNull { line ->
                when {
                    line.startsWith("- ") -> normalizeBulletText(line.removePrefix("- "))
                    line.startsWith("• ") -> normalizeBulletText(line.removePrefix("• "))
                    line.startsWith("* ") -> normalizeBulletText(line.removePrefix("* "))
                    else -> null
                }
            }
    }

    private fun normalizeBulletText(raw: String): String? {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        if (collapsed.isEmpty()) return null
        return collapsed.take(NoteSummaryPolicy.MAX_MEMORY_BULLET_CHARS)
    }

    private fun memoryCharCount(bullets: List<String>): Int = bullets.sumOf { it.length }

    private fun bulletsEquivalent(a: String, b: String): Boolean =
        a.trim().equals(b.trim(), ignoreCase = true)

    private fun resolveBulletIndex(bullets: List<String>, match: String): Int? {
        val trimmed = match.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { oneBased ->
            val index = oneBased - 1
            if (index in bullets.indices) return index
        }
        val exact = bullets.indexOfFirst { it.equals(trimmed, ignoreCase = true) }
        if (exact >= 0) return exact
        val substring = bullets.indexOfFirst { it.contains(trimmed, ignoreCase = true) }
        return substring.takeIf { it >= 0 }
    }

    private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
        forEachIndexed { index, element ->
            if (predicate(index, element)) return true
        }
        return false
    }
}
