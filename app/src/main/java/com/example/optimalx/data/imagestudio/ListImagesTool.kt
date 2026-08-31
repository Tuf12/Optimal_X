package com.example.optimalx.data.imagestudio

import com.example.optimalx.data.dao.FileReferenceDao
import com.example.optimalx.data.model.ConversationScopes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

object ListImagesTool {
    const val DEFAULT_LIMIT = 48
    const val MAX_LIMIT = 200

    data class ResolvedRequest(
        val scope: String,
        val subfolderId: Long?,
        val limit: Int,
    )

    data class Result(
        val ok: Boolean,
        val error: String? = null,
        val scope: String? = null,
        val subfolderId: Long? = null,
        val count: Int = 0,
        val images: List<JsonObject> = emptyList(),
    )

    fun normalizeLimit(limit: Long?): Int {
        if (limit == null || limit <= 0L) return DEFAULT_LIMIT
        return limit.toInt().coerceIn(1, MAX_LIMIT)
    }

    fun resolveListImagesRequest(args: JsonObject): ResolvedRequest? {
        val limit = normalizeLimit(args.longArg("limit"))
        val scopeType = args.stringArg("currentScopeType")
        val imageStudioHub = args.booleanArg("imageStudioHub")
        var listScope = args.stringArg("scope")?.takeIf { it == "all" || it == "subfolder" }
        var subfolderId = args.longArg("subfolderId")

        if (scopeType == ConversationScopes.IMAGE_STUDIO) {
            if (listScope == null) {
                listScope = if (imageStudioHub == true) "all" else "subfolder"
            }
            if (listScope == "subfolder" && subfolderId == null) {
                subfolderId = args.longArg("currentSubfolderId")
            }
        } else if (listScope == null) {
            listScope = if (subfolderId != null) "subfolder" else "all"
        }

        if (listScope == "subfolder" && subfolderId == null) {
            return null
        }
        return ResolvedRequest(scope = listScope, subfolderId = subfolderId, limit = limit)
    }

    suspend fun listImages(
        dao: FileReferenceDao,
        args: JsonObject,
    ): Result {
        val resolved = resolveListImagesRequest(args)
            ?: return Result(ok = false, error = "subfolderId is required when scope=subfolder")

        val rows = when (resolved.scope) {
            "all" -> dao.listAllImagesWithFolderLabels(resolved.limit)
            else -> dao.listSubfolderImagesWithFolderLabels(
                subfolderId = resolved.subfolderId ?: return Result(
                    ok = false,
                    error = "subfolderId is required when scope=subfolder",
                ),
                limit = resolved.limit,
            )
        }

        return Result(
            ok = true,
            scope = resolved.scope,
            subfolderId = if (resolved.scope == "subfolder") resolved.subfolderId else null,
            count = rows.size,
            images = rows.map(::mapImageForTool),
        )
    }

    fun toJson(result: Result): String = buildJsonObject {
        if (!result.ok) {
            put("ok", JsonPrimitive(false))
            put("error", JsonPrimitive(result.error ?: "list_images failed"))
            return@buildJsonObject
        }
        put("ok", JsonPrimitive(true))
        put("scope", JsonPrimitive(result.scope))
        result.subfolderId?.let { put("subfolderId", JsonPrimitive(it)) }
        put("count", JsonPrimitive(result.count))
        put(
            "images",
            buildJsonArray {
                result.images.forEach { add(it) }
            },
        )
    }.toString()

    private fun mapImageForTool(row: FileReferenceWithFolderLabels): JsonObject {
        val ref = row.ref
        val metadata = ImageStudioMetadata.parse(ref.metadataJson)
        val isGenerated = ImageStudioMetadata.isImageStudioMetadata(ref.metadataJson)
        val bytesOnDisk = runCatching {
            val file = File(ref.filePath)
            file.isFile && file.length() > 0L
        }.getOrDefault(false)
        return buildJsonObject {
            put("fileReferenceId", JsonPrimitive(ref.id))
            put("fileName", JsonPrimitive(ref.fileName))
            put("globalId", JsonPrimitive(ref.globalId))
            put("subfolderId", JsonPrimitive(ref.subfolderId))
            put("subfolderName", JsonPrimitive(row.subfolderName))
            put("parentFolderName", JsonPrimitive(row.parentFolderName))
            put("caption", JsonPrimitive(if (isGenerated && metadata != null) promptSnippet(metadata.prompt) else ""))
            put("hasGenerationSettings", JsonPrimitive(isGenerated))
            if (isGenerated && metadata != null) {
                put("tier", JsonPrimitive(metadata.tier))
                put("aspectRatio", JsonPrimitive(metadata.aspectRatio))
            }
            put("bytesOnDisk", JsonPrimitive(bytesOnDisk))
            put("createdAt", JsonPrimitive(ref.createdAt))
        }
    }

    private fun promptSnippet(prompt: String): String {
        val text = prompt.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return ""
        val max = 96
        if (text.length <= max) return text
        return text.take(max - 1) + "…"
    }

    private fun JsonObject.stringArg(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }

    private fun JsonObject.longArg(key: String): Long? =
        stringArg(key)?.toLongOrNull()
            ?: get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun JsonObject.booleanArg(key: String): Boolean? = when (val raw = stringArg(key)?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
