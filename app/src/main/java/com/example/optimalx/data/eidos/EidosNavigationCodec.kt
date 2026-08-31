package com.example.optimalx.data.eidos

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object EidosNavigationCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val listSerializer = ListSerializer(EidosNavigationTarget.serializer())

    fun serializeTargets(targets: List<EidosNavigationTarget>): String? {
        val deduped = dedupeTargets(targets)
        if (deduped.isEmpty()) return null
        return json.encodeToString(listSerializer, deduped)
    }

    fun parseTargetsJson(raw: String?): List<EidosNavigationTarget> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            dedupeTargets(json.decodeFromString(listSerializer, raw))
        }.getOrDefault(emptyList())
    }

    fun normalizeTarget(raw: EidosNavigationTarget?): EidosNavigationTarget? {
        if (raw == null) return null
        val kind = raw.kind.trim()
        val label = raw.label.trim()
        if (kind.isEmpty() || label.isEmpty()) return null
        return EidosNavigationTarget(
            kind = kind,
            label = label,
            subfolderId = raw.subfolderId?.takeIf { it > 0L },
            parentFolderId = raw.parentFolderId?.takeIf { it > 0L },
            parentName = raw.parentName?.trim()?.takeIf { it.isNotEmpty() },
            path = raw.path?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    fun targetKey(target: EidosNavigationTarget): String {
        val normalized = normalizeTarget(target) ?: return ""
        return buildString {
            append(normalized.kind)
            normalized.subfolderId?.let { append("|s:$it") }
            normalized.parentFolderId?.let { append("|p:$it") }
            normalized.path?.let { append("|f:$it") }
        }
    }

    fun dedupeTargets(targets: List<EidosNavigationTarget>): List<EidosNavigationTarget> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<EidosNavigationTarget>(targets.size)
        for (item in targets) {
            val normalized = normalizeTarget(item) ?: continue
            val key = targetKey(normalized)
            if (!seen.add(key)) continue
            out += normalized
        }
        return out
    }

    fun navigationActionLabel(target: EidosNavigationTarget): String {
        val label = target.label.trim().ifEmpty { "Open" }
        return when (target.kind) {
            "parent" -> "Open folder · $label"
            "workshop_file" -> "Open file · $label"
            "workshop" -> "Open workshop · $label"
            "dump_edit" -> "Open DumpEdit"
            "quick_notes" -> "Open quick note · $label"
            else -> "Open note · $label"
        }
    }

    fun formatOptimalxUri(target: EidosNavigationTarget?): String? {
        val normalized = normalizeTarget(target) ?: return null
        return when (normalized.kind) {
            "dump_edit" -> "optimalx://dumpedit"
            "parent" -> {
                val id = normalized.parentFolderId ?: return null
                "optimalx://parent/$id"
            }
            "quick_notes" -> {
                val id = normalized.subfolderId ?: return null
                "optimalx://quick-notes/$id"
            }
            "note", "subfolder" -> {
                val id = normalized.subfolderId ?: return null
                "optimalx://note/$id"
            }
            "workshop" -> {
                val id = normalized.subfolderId ?: return null
                "optimalx://workshop/$id"
            }
            "workshop_file" -> {
                val id = normalized.subfolderId ?: return null
                val path = normalized.path ?: return null
                val encoded = path.split('/').joinToString("/") { segment ->
                    java.net.URLEncoder.encode(segment, Charsets.UTF_8.name())
                        .replace("+", "%20")
                }
                "optimalx://workshop/$id/$encoded"
            }
            else -> null
        }
    }

    /**
     * When the model omits optimalx:// links, append one markdown link per navigation chip
     * so widget / general chat still shows tappable destinations in the reply body.
     */
    fun appendNavigationMarkdownLinks(
        replyText: String,
        targets: List<EidosNavigationTarget>,
    ): String {
        val deduped = dedupeTargets(targets)
        if (deduped.isEmpty()) return replyText
        if (replyText.contains("optimalx://")) return replyText
        val links = deduped.mapNotNull { target ->
            val uri = formatOptimalxUri(target) ?: return@mapNotNull null
            val label = target.label.trim().ifEmpty { return@mapNotNull null }
            "[$label]($uri)"
        }
        if (links.isEmpty()) return replyText
        val body = replyText.trimEnd()
        val suffix = links.joinToString(" · ")
        return if (body.isEmpty()) suffix else "$body\n\n$suffix"
    }

    fun parseOptimalxUri(uri: String?): EidosNavigationTarget? {
        val raw = uri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val withoutScheme = raw.removePrefix("optimalx://")
        if (withoutScheme == raw) return null
        val segments = withoutScheme.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        return when (segments[0].lowercase()) {
            "dumpedit" -> normalizeTarget(
                EidosNavigationTarget(kind = "dump_edit", label = "DumpEdit"),
            )
            "parent" -> {
                val parentFolderId = segments.getOrNull(1)?.toLongOrNull() ?: return null
                normalizeTarget(
                    EidosNavigationTarget(
                        kind = "parent",
                        parentFolderId = parentFolderId,
                        label = "Folder $parentFolderId",
                    ),
                )
            }
            "note" -> {
                val subfolderId = segments.getOrNull(1)?.toLongOrNull() ?: return null
                normalizeTarget(
                    EidosNavigationTarget(
                        kind = "note",
                        subfolderId = subfolderId,
                        label = "Note $subfolderId",
                    ),
                )
            }
            "quick-notes", "quick_notes" -> {
                val subfolderId = segments.getOrNull(1)?.toLongOrNull() ?: return null
                normalizeTarget(
                    EidosNavigationTarget(
                        kind = "quick_notes",
                        subfolderId = subfolderId,
                        label = "Quick note $subfolderId",
                    ),
                )
            }
            "workshop" -> {
                val subfolderId = segments.getOrNull(1)?.toLongOrNull() ?: return null
                if (segments.size <= 2) {
                    normalizeTarget(
                        EidosNavigationTarget(
                            kind = "workshop",
                            subfolderId = subfolderId,
                            label = "Workshop $subfolderId",
                        ),
                    )
                } else {
                    val path = segments.drop(2).joinToString("/") { segment ->
                        runCatching { java.net.URLDecoder.decode(segment, Charsets.UTF_8.name()) }
                            .getOrDefault(segment)
                    }
                    normalizeTarget(
                        EidosNavigationTarget(
                            kind = "workshop_file",
                            subfolderId = subfolderId,
                            path = path,
                            label = path.substringAfterLast('/').ifEmpty { path },
                        ),
                    )
                }
            }
            else -> null
        }
    }
}
