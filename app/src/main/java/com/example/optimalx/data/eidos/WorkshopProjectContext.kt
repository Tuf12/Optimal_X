package com.example.optimalx.data.eidos

import com.example.optimalx.data.model.FileReference

/**
 * Lean Panel Workshop system context: file IDs + optional open-tab excerpt (not full-project 24k dump).
 */
object WorkshopProjectContext {

    /** Max chars of the editor's open file inlined per request (runtime files can be large). */
    const val MAX_OPEN_FILE_EXCERPT_CHARS = 6_000

    fun formatFileManifest(files: List<FileReference>): String {
        if (files.isEmpty()) {
            return "Project files: (none yet — project scaffold may still be initializing)."
        }
        val sorted = files.sortedBy { it.fileName.lowercase() }
        return buildString {
            appendLine("Project files (use fileReferenceId with workshop_read_file / workshop_write_file):")
            sorted.forEach { ref ->
                append("- ")
                append(ref.fileName)
                append(" (")
                append(ref.fileType)
                append(", fileReferenceId=")
                append(ref.id)
                appendLine(")")
            }
        }.trim()
    }

    /**
     * Injected when [com.example.optimalx.data.revision.WorkshopReviewPolicy] routes writes to Diff Review.
     * Keeps the model aligned with the UI badge (items), not tool-call count.
     */
    fun formatDiffReviewStatus(pendingItemCount: Int): String = when {
        pendingItemCount <= 0 ->
            "Diff Review queue: empty (no pending proposals)."
        pendingItemCount == 1 ->
            "Diff Review queue: 1 pending proposal (one file) — user accepts once in Diff Review. More files → call workshop_list_pending_review after edits."
        else ->
            "Diff Review queue: $pendingItemCount pending proposals — one Diff Review row per file. Call workshop_list_pending_review after edits for the file list."
    }

    fun formatOpenFileExcerpt(fileName: String?, content: String?): String? {
        val name = fileName?.trim().orEmpty()
        val body = content?.trim().orEmpty()
        if (name.isEmpty() || body.isEmpty()) return null
        val capped = body.take(MAX_OPEN_FILE_EXCERPT_CHARS)
        val truncated = if (body.length > capped.length) {
            "\n…(truncated — use workshop_read_file for full file)"
        } else {
            ""
        }
        return buildString {
            appendLine("Open editor file excerpt ($name):")
            append(capped)
            append(truncated)
        }.trim()
    }
}
