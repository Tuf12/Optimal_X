package com.example.optimalx.data.eidos

import android.content.Context
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import java.io.File
import java.security.MessageDigest

/**
 * Heuristics for [PanelPlatformSpec.IMPLEMENTATION_PLAN_MD] readiness and accept gates (Phase 4.5).
 */
object ImplementationPlanGate {

    private const val MIN_SUBSTANTIVE_CHARS = 80
    private const val SCAFFOLD_HINT = "_Optional — Plan mode may add"

    fun isSubstantive(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        val body = content.lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("# Implementation plan", ignoreCase = true) ||
                    t.contains(SCAFFOLD_HINT, ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
        return body.length >= MIN_SUBSTANTIVE_CHARS
    }

    fun contentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.trim().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun acceptanceMatchesContent(accepted: Boolean, storedHash: String, content: String?): Boolean {
        if (!accepted || storedHash.isBlank() || content == null) return false
        if (!isSubstantive(content)) return false
        return storedHash == contentHash(content)
    }

    /** True when [IMPLEMENTATION_PLAN_MD] is ready but the user has not tapped **Accept plan** yet. */
    fun needsAcceptance(context: Context, subfolderId: Long): Boolean {
        val content = readContent(context, subfolderId) ?: return false
        if (!isSubstantive(content)) return false
        val accepted = WorkshopProjectPreferences.isImplementationPlanAccepted(context, subfolderId)
        val hash = WorkshopProjectPreferences.getImplementationPlanAcceptedContentHash(context, subfolderId)
        return !acceptanceMatchesContent(accepted, hash, content)
    }

    fun readContent(context: Context, subfolderId: Long): String? {
        val file = File(
            context.filesDir,
            "workshop/$subfolderId/${PanelPlatformSpec.IMPLEMENTATION_PLAN_MD}",
        )
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }
}
