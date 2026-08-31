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

const val WIDGET_WEB_SCOPE_KEY = WebPanelScope.WIDGET

/** Parses editor scope keys from [WebPanel]. */
fun subfolderIdFromWebScopeKey(scopeKey: String): Long? =
    WebPanelScope.subfolderIdFromScopeKey(scopeKey)

fun isWidgetWebScopeKey(scopeKey: String): Boolean = WebPanelScope.isWidget(scopeKey)
