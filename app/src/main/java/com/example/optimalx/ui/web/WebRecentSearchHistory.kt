package com.example.optimalx.ui.web

import android.net.Uri
import androidx.datastore.preferences.core.stringPreferencesKey
import org.json.JSONArray
import org.json.JSONObject

/** Same key as [com.example.optimalx.ui.web.WebPanel] `WEB_RECENT_SEARCHES_KEY`. */
val webPanelRecentSearchesKey = stringPreferencesKey("web_panel_recent_searches_json")

private const val SEARCH_ENGINE_URL = "https://duckduckgo.com/?q="

data class WebRecentSearchItem(
    val scopeKey: String,
    val query: String,
    val url: String,
    val createdAtMillis: Long,
)

/**
 * Decodes the same JSON array as the in-app Web panel, without strict URL validation so
 * widget history can still be shown if stored URLs fail newer validation rules.
 */
fun decodeWebRecentSearchEntriesPermissive(raw: String?): List<WebRecentSearchItem> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val scopeKey = item.optString("scopeKey").trim()
                val queryField = item.optString("query").trim()
                val urlField = item.optString("url").trim()
                val legacyValue = item.optString("value").trim()
                val query = when {
                    queryField.isNotBlank() -> queryField
                    legacyValue.isNotBlank() -> legacyValue
                    else -> ""
                }
                val rawUrl = when {
                    urlField.isNotBlank() -> urlField
                    legacyValue.startsWith("http://", true) || legacyValue.startsWith("https://", true) -> legacyValue
                    legacyValue.isNotBlank() -> "$SEARCH_ENGINE_URL${Uri.encode(legacyValue)}"
                    else -> ""
                }
                if (scopeKey.isBlank() || query.isBlank()) continue
                val url = normalizeStoredSearchUrl(rawUrl, query)
                if (url.isBlank()) continue
                val createdAt = item.optLong("createdAtMillis", 0L)
                add(
                    WebRecentSearchItem(
                        scopeKey = scopeKey,
                        query = query,
                        url = url,
                        createdAtMillis = createdAt,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun normalizeStoredSearchUrl(rawUrl: String, query: String): String {
    val u = rawUrl.trim()
    if (u.startsWith("http://", true) || u.startsWith("https://", true)) return u
    return "$SEARCH_ENGINE_URL${Uri.encode(query)}"
}

fun mergeRecentSearchesNewestFirst(entries: List<WebRecentSearchItem>, max: Int): List<WebRecentSearchItem> {
    return entries
        .sortedByDescending { it.createdAtMillis }
        .distinctBy { item ->
            item.scopeKey to (normalizeWebSearchKey(item.query) ?: item.query.lowercase())
        }
        .take(max)
}

fun encodeWebRecentSearchEntries(items: List<WebRecentSearchItem>): String {
    val array = JSONArray()
    items.forEach { item ->
        array.put(
            JSONObject().apply {
                put("scopeKey", item.scopeKey)
                put("query", item.query)
                put("url", item.url)
                put("createdAtMillis", item.createdAtMillis)
            },
        )
    }
    return array.toString()
}
