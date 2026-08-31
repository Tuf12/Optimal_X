package com.example.optimalx.data.eidos.prefetch

/** Memory / content corpora for prefetch filtering and display tags. */
enum class EidosMemoryCorpus {
    DAILY,
    LTM,
    JOURNAL,
    NOTE,
    FILE,
    CHAT,
    ;

    val displayTag: String
        get() = when (this) {
            DAILY -> "Daily"
            LTM -> "LTM"
            JOURNAL -> "Journal"
            NOTE -> "Note"
            FILE -> "File"
            CHAT -> "Chat"
        }
}
