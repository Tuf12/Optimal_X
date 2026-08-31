package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.prompt.EidosSystemPromptLayers

/** Volatile Image Studio context — composed by [com.example.optimalx.data.eidos.prompt.EidosPromptComposer]. */
object ImageStudioContext {

    fun buildVolatileContext(
        hub: Boolean,
        saveSubfolderId: Long,
        activePreviewFileName: String?,
    ): String = buildList {
        add(EidosSystemPromptLayers.imageStudioRulesBlock(hub, saveSubfolderId))
        activePreviewFileName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            add("")
            add("Active preview image")
            add("Currently previewing: \"$name\"")
        }
    }.joinToString("\n")
}
