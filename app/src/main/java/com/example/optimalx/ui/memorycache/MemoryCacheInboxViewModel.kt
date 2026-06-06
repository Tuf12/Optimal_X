package com.example.optimalx.ui.memorycache

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.repository.EditorRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class MemoryCacheInboxViewModel(
    app: Application,
    private val memoryCacheSubfolderId: Long,
) : AndroidViewModel(app) {

    private val appRef = app as OptimalXApplication
    private val editorRepo = EditorRepository(
        db = appRef.database,
        semanticIndexer = appRef.semanticIndexer,
        appIndexSync = appRef.appIndexSyncService,
        semanticSync = appRef.semanticSyncService,
        semanticChunkBuilder = appRef.semanticChunkBuilder,
    )

    private val json = Json { ignoreUnknownKeys = true }

    val memoryCacheSubfolder: StateFlow<Subfolder?> = editorRepo.getSubfolder(memoryCacheSubfolderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: StateFlow<List<MemoryCacheEntry>> = editorRepo.getNote(memoryCacheSubfolderId)
        .mapLatest { note ->
            val rawContent = note?.content.orEmpty()
            val decoded = decodeMemoryCacheMap(rawContent)

            if (!decoded.isStructured && rawContent.isNotBlank()) {
                listOf(
                    MemoryCacheEntry(
                        subfolderId = memoryCacheSubfolderId,
                        subfolderName = "Memory Cache (Legacy/Raw)",
                        content = rawContent.trim(),
                    ),
                )
            } else {
                decoded.entries.entries
                    .mapNotNull { (subfolderId, content) ->
                        val subfolderName = appRef.database.subfolderDao().getById(subfolderId)?.name
                            ?: "Subfolder #$subfolderId"
                        MemoryCacheEntry(
                            subfolderId = subfolderId,
                            subfolderName = subfolderName,
                            content = content,
                        )
                    }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.subfolderName })
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun decodeMemoryCacheMap(content: String): DecodedMemoryCache {
        if (content.isBlank()) return DecodedMemoryCache(entries = emptyMap(), isStructured = true)
        val parsed = runCatching { json.parseToJsonElement(content) }.getOrNull()
            ?: return DecodedMemoryCache(entries = emptyMap(), isStructured = false)
        val obj = parsed as? JsonObject
            ?: return DecodedMemoryCache(entries = emptyMap(), isStructured = false)
        val out = linkedMapOf<Long, String>()
        obj.forEach { (k, v) ->
            val id = k.toLongOrNull() ?: return@forEach
            val value = (v as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (value.isNotBlank()) out[id] = value
        }
        return DecodedMemoryCache(entries = out, isStructured = true)
    }
}

data class MemoryCacheEntry(
    val subfolderId: Long,
    val subfolderName: String,
    val content: String,
)

private data class DecodedMemoryCache(
    val entries: Map<Long, String>,
    val isStructured: Boolean,
)
