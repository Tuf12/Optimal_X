package com.example.optimalx.data.eidos

import com.example.optimalx.data.dao.ParentFolderDao
import com.example.optimalx.data.dao.SubfolderDao
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.eidos.model.ToolExecutionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class EidosNavigationScope(
    val subfolderId: Long? = null,
    val workshopSubfolderId: Long? = null,
)

data class SubfolderNavContext(
    val subfolderId: Long,
    val subfolderName: String,
    val parentFolderId: Long,
    val parentName: String?,
)

class EidosNavigationLookup(
    private val subfolderDao: SubfolderDao,
    private val parentFolderDao: ParentFolderDao,
) {
    suspend fun subfolderById(subfolderId: Long): SubfolderNavContext? {
        val subfolder = subfolderDao.getById(subfolderId) ?: return null
        if (subfolder.deletedAt != null) return null
        val parent = parentFolderDao.getById(subfolder.parentFolderId)
        return SubfolderNavContext(
            subfolderId = subfolder.id,
            subfolderName = subfolder.name,
            parentFolderId = subfolder.parentFolderId,
            parentName = parent?.name,
        )
    }

    suspend fun parentById(parentFolderId: Long): Pair<Long, String>? {
        val parent = parentFolderDao.getById(parentFolderId) ?: return null
        if (parent.deletedAt != null) return null
        return parent.id to parent.name
    }
}

object EidosNavigationResolver {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseIdFromToolContent(content: String): Long? {
        val match = Regex("""\(id=(\d+)\)""").find(content) ?: return null
        return match.groupValues.getOrNull(1)?.toLongOrNull()
    }

    fun parseSubfolderIdFromToolContent(content: String): Long? {
        val match = Regex("""subfolderId["']?\s*[:=]\s*(\d+)""").find(content) ?: return null
        return match.groupValues.getOrNull(1)?.toLongOrNull()
    }

    fun resolveWorkshopToolSubfolderId(args: JsonObject, scope: EidosNavigationScope): Long? {
        return args.longValue("subfolderId")
            ?: scope.workshopSubfolderId
            ?: scope.subfolderId
    }

    suspend fun resolve(
        toolName: String,
        args: JsonObject,
        result: ToolExecutionResult,
        lookup: EidosNavigationLookup,
        scope: EidosNavigationScope = EidosNavigationScope(),
    ): EidosNavigationTarget? {
        if (result !is ToolExecutionResult.Success) return null
        val content = result.content
        val name = args.stringValue("name")

        return when (toolName) {
            "write_note", "edit_note_section" -> {
                val subfolderId = args.longValue("subfolderId")
                    ?: parseSubfolderIdFromToolContent(content)
                    ?: parseIdFromToolContent(content)
                noteOrQuickNotesTarget(subfolderId, lookup)
            }
            "write_quick_note" -> {
                val subfolderId = parseSubfolderIdFromToolContent(content)
                    ?: args.longValue("subfolderId")
                    ?: parseIdFromToolContent(content)
                quickNotesTarget(subfolderId, lookup)
            }
            "read_note" -> {
                noteOrQuickNotesTarget(args.longValue("subfolderId"), lookup)
            }
            "create_subfolder" -> {
                val subfolderId = parseIdFromToolContent(content)
                subfolderTarget(
                    subfolderId = subfolderId,
                    label = name,
                    lookup = lookup,
                    parentFolderId = args.longValue("parentFolderId"),
                )
            }
            "create_parent_folder" -> {
                parentTarget(parseIdFromToolContent(content), name, lookup)
            }
            "read_dump_edit", "write_dump_edit" -> dumpEditTarget()
            "workshop_write_file",
            "workshop_edit_file",
            "workshop_append_file",
            "workshop_read_file",
            -> {
                val subfolderId = resolveWorkshopToolSubfolderId(args, scope)
                workshopFileTarget(subfolderId, args.stringValue("path"), lookup)
            }
            else -> null
        }
    }

    fun parseToolArguments(argumentsJson: String): JsonObject {
        return runCatching {
            json.parseToJsonElement(argumentsJson).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
    }

    private suspend fun noteOrQuickNotesTarget(
        subfolderId: Long?,
        lookup: EidosNavigationLookup,
    ): EidosNavigationTarget? {
        if (subfolderId == null) return null
        val ctx = lookup.subfolderById(subfolderId)
        if (ctx?.parentName == SystemFolderNames.QUICK_NOTES) {
            return quickNotesTarget(subfolderId, lookup)
        }
        return noteTarget(subfolderId, lookup)
    }

    private suspend fun noteTarget(
        subfolderId: Long,
        lookup: EidosNavigationLookup,
    ): EidosNavigationTarget? {
        val ctx = lookup.subfolderById(subfolderId) ?: return EidosNavigationCodec.normalizeTarget(
            EidosNavigationTarget(kind = "note", subfolderId = subfolderId, label = "Note $subfolderId"),
        )
        return EidosNavigationCodec.normalizeTarget(
            EidosNavigationTarget(
                kind = "note",
                subfolderId = subfolderId,
                label = ctx.subfolderName,
                parentFolderId = ctx.parentFolderId,
                parentName = ctx.parentName,
            ),
        )
    }

    private suspend fun quickNotesTarget(
        subfolderId: Long?,
        lookup: EidosNavigationLookup,
    ): EidosNavigationTarget? {
        if (subfolderId == null) return null
        val ctx = lookup.subfolderById(subfolderId)
        return EidosNavigationCodec.normalizeTarget(
            EidosNavigationTarget(
                kind = "quick_notes",
                subfolderId = subfolderId,
                label = ctx?.subfolderName ?: "Quick note $subfolderId",
                parentFolderId = ctx?.parentFolderId,
                parentName = ctx?.parentName,
            ),
        )
    }

    private suspend fun subfolderTarget(
        subfolderId: Long?,
        label: String?,
        lookup: EidosNavigationLookup,
        parentFolderId: Long?,
    ): EidosNavigationTarget? {
        if (subfolderId == null) return null
        val ctx = lookup.subfolderById(subfolderId)
        return EidosNavigationCodec.normalizeTarget(
            EidosNavigationTarget(
                kind = "subfolder",
                subfolderId = subfolderId,
                label = label?.takeIf { it.isNotBlank() } ?: ctx?.subfolderName ?: "Note $subfolderId",
                parentFolderId = parentFolderId ?: ctx?.parentFolderId,
                parentName = ctx?.parentName,
            ),
        )
    }

    private suspend fun parentTarget(
        parentFolderId: Long?,
        label: String?,
        lookup: EidosNavigationLookup,
    ): EidosNavigationTarget? {
        if (parentFolderId == null) return null
        val parent = lookup.parentById(parentFolderId)
        return EidosNavigationCodec.normalizeTarget(
            EidosNavigationTarget(
                kind = "parent",
                parentFolderId = parentFolderId,
                label = label?.takeIf { it.isNotBlank() } ?: parent?.second ?: "Folder $parentFolderId",
            ),
        )
    }

    private suspend fun workshopFileTarget(
        subfolderId: Long?,
        filePath: String?,
        lookup: EidosNavigationLookup,
    ): EidosNavigationTarget? {
        if (subfolderId == null) return null
        val path = filePath?.trim().orEmpty()
        if (path.isEmpty()) return null
        val ctx = lookup.subfolderById(subfolderId)
        val fileName = path.substringAfterLast('/').ifEmpty { path }
        return EidosNavigationCodec.normalizeTarget(
            EidosNavigationTarget(
                kind = "workshop_file",
                subfolderId = subfolderId,
                path = path,
                label = fileName,
                parentFolderId = ctx?.parentFolderId,
                parentName = ctx?.parentName,
            ),
        )
    }

    private fun dumpEditTarget(): EidosNavigationTarget? =
        EidosNavigationCodec.normalizeTarget(
            EidosNavigationTarget(kind = "dump_edit", label = "DumpEdit"),
        )

    private fun JsonObject.longValue(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }

    private fun JsonObject.stringValue(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
