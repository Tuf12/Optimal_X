package com.example.optimalx.data.eidos

/**
 * Merge policy for [write_note]: set initial body when empty, append when not.
 * Non-empty merges route through [com.example.optimalx.data.revision.NoteWriteRouter] (Diff Review).
 */
object NoteWriteMerge {

    data class Result(
        val mergedMarkdown: String,
        val successMessage: String,
        val appended: Boolean,
    )

    fun merge(currentMarkdown: String, additionMarkdown: String): Result {
        val current = currentMarkdown
        val addition = additionMarkdown
        if (current.isBlank()) {
            return Result(
                mergedMarkdown = addition,
                successMessage = "Note created",
                appended = false,
            )
        }
        val separator = "\n\n"
        return Result(
            mergedMarkdown = current + separator + addition,
            successMessage = "Content appended",
            appended = true,
        )
    }
}
