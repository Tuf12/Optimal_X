package com.example.optimalx.data.imagestudio

/**
 * Parse Eidos "Image Studio draft" blocks from assistant markdown (desktop parity).
 */
data class ImageStudioDraft(
    val prompt: String,
    val negativePrompt: String = "",
    val aspectRatio: String? = null,
    val suggestedName: String? = null,
    val tier: String? = null,
)

object ImageStudioDraftParser {
    private val DRAFT_HEADING = Regex("###\\s+Image Studio draft\\b", RegexOption.IGNORE_CASE)
    private val ASPECT_RATIOS = setOf("1:1", "2:3", "9:16", "3:2", "16:9")
    private val MULTILINE_FIELDS = setOf("Prompt", "Negative")

    fun hasImageStudioDraft(markdown: String?): Boolean =
        DRAFT_HEADING.containsMatchIn(markdown.orEmpty())

    fun extractDraftBlock(markdown: String?): String? {
        val text = markdown.orEmpty()
        val headingMatch = DRAFT_HEADING.find(text) ?: return null
        val start = headingMatch.range.last + 1
        val rest = text.substring(start).replace(Regex("^\\s*\\n"), "")
        val nextHeading = Regex("\\n###\\s+").find(rest)?.range?.first ?: -1
        val block = if (nextHeading >= 0) rest.substring(0, nextHeading) else rest
        return block.trim().ifBlank { null }
    }

    fun parse(markdown: String?): ImageStudioDraft? {
        val block = extractDraftBlock(markdown) ?: return null
        val prompt = fieldValue(block, "Prompt") ?: return null
        val negativeRaw = fieldValue(block, "Negative")
        val negativePrompt = when {
            negativeRaw.isNullOrBlank() -> ""
            negativeRaw.matches(Regex("^(none|n/a|—|-)$", RegexOption.IGNORE_CASE)) -> ""
            else -> negativeRaw
        }
        return ImageStudioDraft(
            prompt = prompt,
            negativePrompt = negativePrompt,
            aspectRatio = normalizeAspect(fieldValue(block, "Aspect")),
            suggestedName = normalizeFileName(fieldValue(block, "Suggested name")),
            tier = normalizeTier(fieldValue(block, "Tier")),
        )
    }

    private fun fieldValue(block: String, fieldName: String): String? {
        val escaped = Regex.escape(fieldName)
        val headerRe = Regex("^\\*\\*$escaped:\\*\\*\\s*(.*)$", RegexOption.IGNORE_CASE)
        val lines = block.split('\n')
        for (i in lines.indices) {
            val header = headerRe.find(lines[i]) ?: continue
            val first = header.groupValues[1].trim()
            if (fieldName !in MULTILINE_FIELDS) {
                return first.ifBlank { null }
            }
            val parts = mutableListOf<String>()
            if (first.isNotEmpty()) parts += first
            for (j in i + 1 until lines.size) {
                if (Regex("^\\*\\*[^:\\n]+:\\*\\*").containsMatchIn(lines[j])) break
                parts += lines[j]
            }
            return parts.joinToString("\n").trim().ifBlank { null }
        }
        return null
    }

    fun normalizeAspect(raw: String?): String? {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return null
        val direct = Regex("\\b(\\d+:\\d+)\\b").find(text)?.groupValues?.get(1)
        val ratio = direct ?: text
        return ratio.takeIf { it in ASPECT_RATIOS }
    }

    fun normalizeTier(raw: String?): String? = when (raw.orEmpty().trim().lowercase()) {
        "quality", "9b" -> "quality"
        "draft", "4b" -> "draft"
        else -> null
    }

    fun normalizeFileName(raw: String?): String? {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return null
        return text.replace(Regex("\\.(png|webp|jpe?g)$", RegexOption.IGNORE_CASE), "")
    }
}
