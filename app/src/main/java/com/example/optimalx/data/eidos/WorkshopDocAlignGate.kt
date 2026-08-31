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
     * Runtime files that feed a given spec — conservatively, *all* runtime files in [scope].
     *
     * Since specs are only ever rewritten by the align pass itself, "code matches docs" means every
     * per-spec fingerprint is stable and the whole pass is skipped (zero tokens). When any code file
     * changes we re-review every in-scope spec, so a change can never silently skip a spec that
     * should have been updated. (The [specFileName] parameter is kept for a future finer mapping.)
     */
    @Suppress("UNUSED_PARAMETER")
    fun runtimeInputsForSpec(specFileName: String, scope: WorkshopDocAlignScope): List<String> =
        runtimeFileNames(scope).map { it.lowercase(Locale.US) }

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
    ): Boolean =
        staleSpecs(context, db, subfolderId, scope, updateSection, currentFileId, inMemoryContent).isEmpty()

    /**
     * Spec `.md` files whose per-spec fingerprint (spec text + relevant code) changed since the last
     * successful align — the only files a new align pass needs to touch. Empty ⇒ nothing to sync.
     */
    suspend fun staleSpecs(
        context: Context,
        db: AppDatabase,
        subfolderId: Long,
        scope: WorkshopDocAlignScope,
        updateSection: WorkshopUpdateSection? = null,
        currentFileId: Long? = null,
        inMemoryContent: String = "",
    ): List<String> {
        val stored = WorkshopProjectPreferences.getAlignSpecFingerprints(context, subfolderId, scope)
        val refs = db.fileReferenceDao().getBySubfolderOnce(subfolderId)
        val current = computeSpecFingerprints(refs, scope, updateSection, currentFileId, inMemoryContent)
        return current.filter { (spec, fp) -> stored[spec] != fp }.keys.toList()
    }

    /**
     * One-shot align payload: the current code (authoritative) plus the current text of each stale
     * spec, so Eidos can rewrite specs without a workshop_read_file tool loop.
     */
    suspend fun buildInlinePayload(
        db: AppDatabase,
        subfolderId: Long,
        scope: WorkshopDocAlignScope,
        staleSpecs: List<String>,
        currentFileId: Long? = null,
        inMemoryContent: String = "",
    ): String {
        val refs = db.fileReferenceDao().getBySubfolderOnce(subfolderId)
        val runtimeNeeded = staleSpecs
            .flatMap { runtimeInputsForSpec(it, scope) }
            .distinct()
        return buildString {
            append("=== CURRENT CODE (authoritative — describe this in the specs) ===\n")
            for (name in runtimeNeeded) {
                val text = fileTextByName(refs, name, currentFileId, inMemoryContent)
                append("\n----- ").append(name).append(" -----\n")
                append(text.ifBlank { "(empty)" })
                append("\n")
            }
            append("\n=== SPEC FILES TO REVIEW (rewrite only if out of date) ===\n")
            for (spec in staleSpecs) {
                val text = fileTextByName(refs, spec, currentFileId, inMemoryContent)
                append("\n----- ").append(spec).append(" (current) -----\n")
                append(text.ifBlank { "(missing — create it)" })
                append("\n")
            }
        }
    }

    private fun computeSpecFingerprints(
        refs: List<FileReference>,
        scope: WorkshopDocAlignScope,
        updateSection: WorkshopUpdateSection?,
        currentFileId: Long?,
        inMemoryContent: String,
    ): Map<String, String> {
        val specs = scope.markdownFiles(updateSection)
        return specs.associateWith { spec ->
            val md = MessageDigest.getInstance("SHA-256")
            md.update("spec:${spec.lowercase(Locale.US)}".toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(fileTextByName(refs, spec, currentFileId, inMemoryContent).toByteArray(Charsets.UTF_8))
            for (rt in runtimeInputsForSpec(spec, scope)) {
                md.update(0)
                md.update("rt:$rt".toByteArray(Charsets.UTF_8))
                md.update(0)
                md.update(fileTextByName(refs, rt, currentFileId, inMemoryContent).toByteArray(Charsets.UTF_8))
            }
            md.digest().joinToString("") { b -> "%02x".format(b) }
        }
    }

    private fun fileTextByName(
        refs: List<FileReference>,
        name: String,
        currentFileId: Long?,
        inMemoryContent: String,
    ): String {
        val ref = refs.firstOrNull { it.fileName.equals(name, ignoreCase = true) } ?: return ""
        return fileText(ref, currentFileId, inMemoryContent)
    }

    suspend fun recordAlignFingerprint(
        context: Context,
        db: AppDatabase,
        subfolderId: Long,
        scope: WorkshopDocAlignScope,
        updateSection: WorkshopUpdateSection? = null,
    ) {
        val refs = db.fileReferenceDao().getBySubfolderOnce(subfolderId)
        WorkshopProjectPreferences.setAlignSpecFingerprints(
            context,
            subfolderId,
            scope,
            computeSpecFingerprints(refs, scope, updateSection, null, ""),
        )
        // Keep legacy combined + md-only digests for telemetry / migration.
        val fingerprint = computeFingerprint(db, subfolderId, scope, updateSection, null, "")
        WorkshopProjectPreferences.setAlignFingerprint(context, subfolderId, scope, fingerprint)
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
