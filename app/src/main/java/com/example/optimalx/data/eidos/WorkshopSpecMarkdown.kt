package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.FileReference
import java.io.File

/**
 * Loads bounded workshop spec `.md` bodies for Eidos context (never runtime HTML/CSS/JS).
 */
object WorkshopSpecMarkdown {

    const val MAX_TOTAL_CHARS = 6_000
    const val MAX_PER_FILE_CHARS = 2_000

    fun loadBounded(
        files: List<FileReference>,
        inMemoryByFileId: Map<Long, String> = emptyMap(),
        maxTotal: Int = MAX_TOTAL_CHARS,
        maxPerFile: Int = MAX_PER_FILE_CHARS,
    ): String {
        val specNames = PanelPlatformSpec.SPEC_MARKDOWN_FILES.map { it.lowercase() }.toSet()
        val refs = files
            .filter { ref ->
                ref.fileType.equals("md", ignoreCase = true) &&
                    ref.fileName.lowercase() in specNames
            }
            .sortedBy { PanelPlatformSpec.SPEC_MARKDOWN_FILES.indexOf(it.fileName) }

        if (refs.isEmpty()) return ""

        val builder = StringBuilder()
        var remaining = maxTotal
        for (ref in refs) {
            if (remaining <= 0) break
            val body = (inMemoryByFileId[ref.id]
                ?: runCatching { File(ref.filePath).readText() }.getOrDefault(""))
                .trim()
            if (body.isEmpty()) continue
            val capped = body.take(minOf(maxPerFile, remaining))
            if (builder.isNotEmpty()) builder.appendLine().appendLine()
            builder.append("### ").append(ref.fileName).appendLine().appendLine(capped)
            remaining -= capped.length
        }
        return builder.toString().trim()
    }
}
