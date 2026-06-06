package com.example.optimalx.ui.web

import java.util.Locale

/** Normalized key for web search history and web-scoped chat thread identity. */
fun normalizeWebSearchKey(rawQuery: String): String? =
    rawQuery.trim()
        .lowercase(Locale.US)
        .replace(Regex("\\s+"), " ")
        .takeIf { it.isNotBlank() }

/** Human-readable title for a web conversation (not the normalized key). */
fun displayWebSearchTitle(rawQuery: String): String =
    rawQuery.trim().replace(Regex("\\s+"), " ")

const val WIDGET_WEB_SCOPE_KEY = "widget_quick_web"

/** Parses `editor:{parentId}:{subfolderId}` scope keys from [WebPanel]. */
fun subfolderIdFromWebScopeKey(scopeKey: String): Long? {
    if (!scopeKey.startsWith("editor:")) return null
    val parts = scopeKey.split(":")
    if (parts.size < 3) return null
    return parts[2].toLongOrNull()
}

fun isWidgetWebScopeKey(scopeKey: String): Boolean = scopeKey == WIDGET_WEB_SCOPE_KEY
