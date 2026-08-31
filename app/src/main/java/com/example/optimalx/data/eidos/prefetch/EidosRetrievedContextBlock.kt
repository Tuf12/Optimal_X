package com.example.optimalx.data.eidos.prefetch

import com.example.optimalx.data.semantic.SemanticChunkHit

data class RetrievedChunk(
    val hit: SemanticChunkHit,
    val sourceTag: String,
)

object EidosRetrievedContextBlock {

    const val SECTION_HEADER = "## Retrieved context"

    fun format(
        chunks: List<RetrievedChunk>,
        maxChars: Int,
        maxChunks: Int,
    ): String? {
        if (chunks.isEmpty()) return null
        val capped = chunks.take(maxChunks.coerceAtLeast(1))
        val body = buildString {
            appendLine("Passages relevant to the user's message (orientation — call search_semantic for more or to edit):")
            var budget = maxChars.coerceAtLeast(200)
            capped.forEachIndexed { index, chunk ->
                if (budget <= 0) return@forEachIndexed
                val hit = chunk.hit
                val header = buildString {
                    append("[${index + 1}] ${chunk.sourceTag}")
                    if (hit.location.isNotBlank()) append(" · ${hit.location}")
                    append(" (score=${"%.2f".format(hit.score)})")
                }
                appendLine(header)
                val text = hit.chunkText.trim()
                val excerpt = if (text.length <= budget) text else text.take(budget) + "…"
                appendLine(excerpt)
                appendLine()
                budget -= excerpt.length + header.length + 2
            }
        }.trim()
        if (body.isBlank()) return null
        return "$SECTION_HEADER\n$body"
    }
}
