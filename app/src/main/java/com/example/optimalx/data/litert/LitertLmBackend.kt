package com.example.optimalx.data.litert

enum class LitertLmBackend(val wire: String) {
    GPU("gpu"),
    CPU("cpu"),
    ;

    companion object {
        fun fromWire(raw: String?): LitertLmBackend =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: GPU
    }
}
