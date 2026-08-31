package com.example.optimalx.data.eidos

/**
 * Thresholds for note body prompt injection and stored [Memory]/[Content] sections.
 * See app/docs/systems/NOTE_SUMMARY.md.
 */
object NoteSummaryPolicy {

    const val MEMORY_HEADER = "[Memory]"
    const val CONTENT_HEADER = "[Content]"

    /** Full note body inlined in subfolder prompt when at or below this size. */
    const val INLINE_NOTE_MAX_CHARS = 8_000

    /** Large-note nudge to use write_note_summary when folder memory is empty. */
    const val FOLD_TRIGGER_CHARS = 4_000

    /** Max total characters across all [Memory] bullets. */
    const val MAX_MEMORY_CHARS = 1_200

    /** Max bullet count in [Memory]. */
    const val MAX_MEMORY_BULLETS = 10

    /** Max stored [Content] digest length if the user still has one saved. */
    const val MAX_CONTENT_DIGEST_CHARS = 800

    /** Max length of a single memory bullet line (excluding leading "- "). */
    const val MAX_MEMORY_BULLET_CHARS = 280
}
