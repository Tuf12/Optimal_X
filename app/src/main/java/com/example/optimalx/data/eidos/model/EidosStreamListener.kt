package com.example.optimalx.data.eidos.model

/** Accumulated Kimi stream text for live chat preview during an in-flight send. */
data class EidosStreamUpdate(
    val reasoningText: String = "",
    val contentText: String = "",
)

fun interface EidosStreamListener {
    fun onStreamUpdate(update: EidosStreamUpdate)
}
