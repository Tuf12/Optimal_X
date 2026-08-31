package com.example.optimalx.ui.web

/**
 * Canonical browser scope keys for [WebPanel] persistence and isolation.
 *
 * See `app/docs/systems/WEB_SYSTEM.md` — Scope behavior rule.
 */
object WebPanelScope {

    /** Widget quick-access browser (home-screen widget). */
    const val WIDGET = "widget:quick_web"

    /** Legacy widget scope key — reads only. */
    const val LEGACY_WIDGET = "widget_quick_web"

    private const val EDITOR_PREFIX = "editor:subfolder:"
    private val LEGACY_EDITOR_SCOPE_REGEX = Regex("^editor_subfolder_(\\d+)$")

    /** Editor Web tab for one subfolder (project). */
    fun editor(subfolderId: Long): String = "$EDITOR_PREFIX$subfolderId"

    /**
     * Normalizes legacy and transitional scope keys to the canonical form used for writes.
     * Editor scopes collapse to [editor]; widget aliases collapse to [WIDGET].
     */
    fun normalize(scopeKey: String): String {
        val trimmed = scopeKey.trim()
        if (trimmed.isBlank()) return trimmed
        when (trimmed) {
            LEGACY_WIDGET, WIDGET -> return WIDGET
        }
        LEGACY_EDITOR_SCOPE_REGEX.matchEntire(trimmed)?.let { match ->
            val id = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return trimmed
            return editor(id)
        }
        if (trimmed.startsWith("editor:")) {
            val subfolderId = parseEditorSubfolderIdRaw(trimmed) ?: return trimmed
            return editor(subfolderId)
        }
        return trimmed
    }

    /** True when [active] and [candidate] refer to the same browser scope. */
    fun matches(active: String, candidate: String): Boolean =
        normalize(active) == normalize(candidate)

    /** Subfolder id for editor scopes; null for widget and unknown keys. */
    fun subfolderIdFromScopeKey(scopeKey: String): Long? {
        val normalized = normalize(scopeKey)
        if (!normalized.startsWith(EDITOR_PREFIX)) return null
        return normalized.removePrefix(EDITOR_PREFIX).toLongOrNull()
    }

    private fun parseEditorSubfolderIdRaw(scopeKey: String): Long? {
        LEGACY_EDITOR_SCOPE_REGEX.matchEntire(scopeKey)?.let { match ->
            return match.groupValues.getOrNull(1)?.toLongOrNull()
        }
        if (scopeKey.startsWith(EDITOR_PREFIX)) {
            return scopeKey.removePrefix(EDITOR_PREFIX).toLongOrNull()
        }
        if (scopeKey.startsWith("editor:")) {
            val parts = scopeKey.split(':')
            if (parts.size == 3) return parts[2].toLongOrNull()
        }
        return null
    }

    fun isWidget(scopeKey: String): Boolean = normalize(scopeKey) == WIDGET

    fun isEditor(scopeKey: String): Boolean = subfolderIdFromScopeKey(scopeKey) != null
}
