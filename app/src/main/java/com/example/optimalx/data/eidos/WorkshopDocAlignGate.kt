package com.example.optimalx.data.eidos

import android.content.Context
import com.example.optimalx.data.db.AppDatabase
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.data.preferences.WorkshopProjectPreferences
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Skips redundant Eidos doc-align passes when runtime + spec fingerprints are unchanged
 * since the last successful align for the same [WorkshopDocAlignScope].
 */
object WorkshopDocAlignGate {

    fun runtimeFileNames(scope: WorkshopDocAlignScope): List<String> = when (scope) {
        WorkshopDocAlignScope.DESIGN -> listOf("index.html", "style.css", "script.js")
        WorkshopDocAlignScope.FINISH,
        WorkshopDocAlignScope.UPDATE,
        -> listOf("index.html", "style.css", "bridge.js", "script.js")
    }

    /**
     * @return stored fingerprint for [scope], or empty if never aligned / legacy project.
     */
    fun getStoredFingerprint(context: Context, subfolderId: Long, scope: WorkshopDocAlignScope): String =
        WorkshopProjectPreferences.getAlignFingerprint(context, subfolderId, scope)

    suspend fun computeFingerprint(
        db: AppDatabase,
        subfolderId: Long,
        scope: WorkshopDocAlignScope,
        updateSection: WorkshopUpdateSection? = null,
        currentFileId: Long? = null,
        inMemoryContent: String = "",
    ): String {
        val refs = db.fileReferenceDao().getBySubfolderOnce(subfolderId)
        val runtimeDigest = computeRuntimeDigest(refs, scope, currentFileId, inMemoryContent)
        val specDigest = computeSpecDigest(refs, scope, updateSection, currentFileId, inMemoryContent)
        return "$runtimeDigest|$specDigest"
    }

    suspend fun shouldSkipAlign(
        context: Context,
        db: AppDatabase,
        subfolderId: Long,
        scope: WorkshopDocAlignScope,
        updateSection: WorkshopUpdateSection? = null,
        currentFileId: Long? = null,
        inMemoryContent: String = "",
    ): Boolean {
        val stored = getStoredFingerprint(context, subfolderId, scope)
        if (stored.isBlank()) return false
        val current = computeFingerprint(db, subfolderId, scope, updateSection, currentFileId, inMemoryContent)
        return current == stored
    }

    suspend fun recordAlignFingerprint(
        context: Context,
        db: AppDatabase,
        subfolderId: Long,
        scope: WorkshopDocAlignScope,
        updateSection: WorkshopUpdateSection? = null,
    ) {
        val fingerprint = computeFingerprint(db, subfolderId, scope, updateSection, null, "")
        WorkshopProjectPreferences.setAlignFingerprint(context, subfolderId, scope, fingerprint)
        // Keep legacy md-only digest for telemetry / migration.
        val refs = db.fileReferenceDao().getBySubfolderOnce(subfolderId)
        val specDigest = computeSpecDigest(refs, scope, updateSection, null, "")
        WorkshopProjectPreferences.setDocDigestAtLastCodeSync(context, subfolderId, specDigest)
    }

    private fun computeRuntimeDigest(
        refs: List<FileReference>,
        scope: WorkshopDocAlignScope,
        currentFileId: Long?,
        inMemoryContent: String,
    ): String {
        val names = runtimeFileNames(scope).map { it.lowercase(Locale.US) }
        val md = MessageDigest.getInstance("SHA-256")
        for (name in names) {
            val ref = refs.firstOrNull { it.fileName.equals(name, ignoreCase = true) } ?: continue
            val text = fileText(ref, currentFileId, inMemoryContent)
            md.update(name.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(text.toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun computeSpecDigest(
        refs: List<FileReference>,
        scope: WorkshopDocAlignScope,
        updateSection: WorkshopUpdateSection?,
        currentFileId: Long?,
        inMemoryContent: String,
    ): String {
        val targetNames = scope.markdownFiles(updateSection)
            .map { it.lowercase(Locale.US) }
            .toSet()
        val md = MessageDigest.getInstance("SHA-256")
        refs.filter { it.fileType.equals("md", ignoreCase = true) }
            .filter { it.fileName.lowercase(Locale.US) in targetNames }
            .sortedBy { it.fileName.lowercase(Locale.US) }
            .forEach { ref ->
                val text = fileText(ref, currentFileId, inMemoryContent)
                md.update(ref.fileName.toByteArray(Charsets.UTF_8))
                md.update(0)
                md.update(text.toByteArray(Charsets.UTF_8))
            }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun fileText(ref: FileReference, currentFileId: Long?, inMemoryContent: String): String =
        if (ref.id == currentFileId) {
            inMemoryContent
        } else {
            runCatching { File(ref.filePath).readText() }.getOrDefault("")
        }
}
