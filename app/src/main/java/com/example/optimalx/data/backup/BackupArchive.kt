package com.example.optimalx.data.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Spec: app/docs/architecture/OPTIMALX_LINK.md.
 *
 * Pure stream / file-system archive core for OptimalX snapshots. No Android
 * dependencies — every channel (SAF, the phone-side Ktor server, the PySide6
 * desktop app proxying through Kotlin via tests) routes through these
 * functions so behavior stays identical.
 *
 * The archive shape:
 *
 * ```
 * manifest.json
 * database/optimalx.db
 * files/optimalx_files/...
 * files/workshop/<subfolderId>/...
 * ```
 *
 * See OPTIMALX_LINK.md for the manifest contract and format-version policy.
 */
object BackupArchive {

    const val MANIFEST_ENTRY: String = "manifest.json"

    /** Path inside the archive for the SQLite database file. */
    const val DB_DIR_PREFIX: String = "database/"

    /** Path prefix inside the archive for the attachments directory. */
    const val FILES_ATTACHMENTS_PREFIX: String = "files/optimalx_files/"

    /** Path prefix inside the archive for workshop projects. */
    const val FILES_WORKSHOP_PREFIX: String = "files/workshop/"

    /** Optional DumpEdit scratch buffer persisted outside Room. */
    const val DUMP_EDIT_ENTRY: String = "preferences/dump_edit.json"

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Writes a full snapshot to [out] as a zip with the structure described above.
     *
     * @param out destination stream. The caller owns the lifecycle and must close it.
     * @param manifest manifest written as the first entry.
     * @param dbFile SQLite DB file. Required. Written as `database/<dbFile.name>`.
     * @param attachmentsDir contents of this directory are written under
     *   [FILES_ATTACHMENTS_PREFIX]. Skipped when null or non-existent.
     * @param workshopDir contents of this directory are written under
     *   [FILES_WORKSHOP_PREFIX]. Skipped when null or non-existent.
     * @param dumpEditJson optional DumpEdit buffer JSON written as [DUMP_EDIT_ENTRY].
     */
    fun writeBackupZip(
        out: OutputStream,
        manifest: BackupManifest,
        dbFile: File,
        attachmentsDir: File?,
        workshopDir: File?,
        dumpEditJson: String? = null,
    ) {
        require(dbFile.isFile) { "dbFile must point to an existing SQLite database: ${dbFile.path}" }
        val zip = ZipOutputStream(BufferedOutputStream(out))
        try {
            writeTextEntry(zip, MANIFEST_ENTRY, json.encodeToString(manifest))
            writeFileEntry(zip, DB_DIR_PREFIX + dbFile.name, dbFile)
            attachmentsDir?.let { addDirectoryToZip(zip, FILES_ATTACHMENTS_PREFIX, it) }
            workshopDir?.let { addDirectoryToZip(zip, FILES_WORKSHOP_PREFIX, it) }
            if (!dumpEditJson.isNullOrBlank()) {
                writeTextEntry(zip, DUMP_EDIT_ENTRY, dumpEditJson)
            }
            zip.flush()
        } finally {
            zip.close()
        }
    }

    /**
     * Extracts a snapshot zip from [input] into [destDir]. The destination is
     * created if missing.
     *
     * Records every entry name encountered (in archive order) on the returned
     * [ExtractedBackup], including duplicates, so callers can present a useful
     * error message when something is missing. Protects against zip-slip by
     * canonical-path checking before writing each entry.
     */
    fun extractBackupZip(input: InputStream, destDir: File): ExtractedBackup {
        if (!destDir.exists()) destDir.mkdirs()
        val canonicalDest = destDir.canonicalFile
        val entryNames = mutableListOf<String>()
        var manifestFile: File? = null

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryNames += entry.name
                if (!entry.isDirectory) {
                    val outFile = File(canonicalDest, entry.name).canonicalFile
                    val destPath = canonicalDest.path + File.separator
                    if (outFile.path != canonicalDest.path && !outFile.path.startsWith(destPath)) {
                        throw IllegalStateException("Invalid archive entry path: ${entry.name}")
                    }
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zip.copyTo(fos) }
                    if (outFile.name.equals(MANIFEST_ENTRY, ignoreCase = true) && manifestFile == null) {
                        // Prefer the shallowest manifest if multiple are present.
                        manifestFile = outFile
                    } else if (outFile.name.equals(MANIFEST_ENTRY, ignoreCase = true)) {
                        val current = manifestFile
                        if (current != null && depthOf(outFile, canonicalDest) < depthOf(current, canonicalDest)) {
                            manifestFile = outFile
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        return ExtractedBackup(
            root = canonicalDest,
            manifestFile = manifestFile,
            entryNames = entryNames.toList(),
        )
    }

    /**
     * Validates an extracted snapshot and produces a [RestorePlan]. Does not
     * mutate anything.
     *
     * @param extracted result of [extractBackupZip].
     * @param hostSchemaVersion the running app's DB schema version. Backups
     *   declaring a higher version are rejected since the running app does not
     *   know how to read them.
     * @param dbFileName name of the host's SQLite file (e.g. `optimalx.db`).
     *   Used to locate the DB file inside the archive when the standard path
     *   `database/<dbFileName>` is missing (e.g. an archive wrapped in an
     *   extra folder).
     */
    fun planRestore(
        extracted: ExtractedBackup,
        hostSchemaVersion: Int,
        dbFileName: String,
    ): RestorePlan {
        val manifestFile = extracted.manifestFile
            ?: locateManifestFile(extracted.root)
            ?: return RestorePlan.Failure(
                FailureReason.MissingManifest(extracted.entryNames),
            )

        val parsed = parseManifest(manifestFile)
        val manifest = when (parsed) {
            is ManifestParse.Ok -> parsed.manifest
            ManifestParse.Empty -> return RestorePlan.Failure(FailureReason.EmptyManifest)
            is ManifestParse.Invalid -> return RestorePlan.Failure(FailureReason.InvalidManifest(parsed.reason))
        }

        if (manifest.formatVersion != BackupManifest.CURRENT_FORMAT_VERSION) {
            return RestorePlan.Failure(FailureReason.UnsupportedFormat(manifest.formatVersion))
        }
        if (manifest.dbVersion > hostSchemaVersion) {
            return RestorePlan.Failure(
                FailureReason.BackupNewerThanHost(
                    backupDbVersion = manifest.dbVersion,
                    hostDbVersion = hostSchemaVersion,
                ),
            )
        }

        val effectiveRoot = manifestFile.parentFile ?: extracted.root
        val dbSource = locateDatabaseFile(effectiveRoot, dbFileName)
            ?: return RestorePlan.Failure(FailureReason.MissingDatabase(dbFileName))

        return RestorePlan.Ok(
            manifest = manifest,
            dbSource = dbSource,
            effectiveRoot = effectiveRoot,
        )
    }

    /**
     * Convenience helper used by code that already has a [RestorePlan.Ok] to
     * iterate every file under [FILES_ATTACHMENTS_PREFIX] in archive order.
     */
    fun attachmentsRoot(plan: RestorePlan.Ok): File = File(plan.effectiveRoot, FILES_ATTACHMENTS_PREFIX)

    /** Workshop subtree corresponding to [plan]. */
    fun workshopRoot(plan: RestorePlan.Ok): File = File(plan.effectiveRoot, FILES_WORKSHOP_PREFIX)

    /** DumpEdit JSON sidecar when present in the archive; null for older backups. */
    fun dumpEditFile(plan: RestorePlan.Ok): File? {
        val direct = File(plan.effectiveRoot, DUMP_EDIT_ENTRY)
        return direct.takeIf { it.isFile }
    }

    fun parseDumpEditPayload(file: File): DumpEditBackupPayload? = runCatching {
        json.decodeFromString<DumpEditBackupPayload>(file.readText())
    }.getOrNull()

    // -------- private helpers --------

    private fun writeTextEntry(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun writeFileEntry(zip: ZipOutputStream, path: String, file: File) {
        zip.putNextEntry(ZipEntry(path))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addDirectoryToZip(zip: ZipOutputStream, zipPrefix: String, dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(dir).path.replace('\\', '/')
                writeFileEntry(zip, zipPrefix + relative, file)
            }
    }

    private fun locateManifestFile(root: File): File? {
        if (!root.exists()) return null
        return root.walkTopDown()
            .filter { it.isFile && it.name.equals(MANIFEST_ENTRY, ignoreCase = true) }
            .minByOrNull { depthOf(it, root) }
    }

    private fun locateDatabaseFile(root: File, dbFileName: String): File? {
        val direct = File(root, DB_DIR_PREFIX + dbFileName)
        if (direct.isFile) return direct
        return root.walkTopDown()
            .firstOrNull { it.isFile && it.name.equals(dbFileName, ignoreCase = true) }
    }

    private fun depthOf(file: File, root: File): Int {
        val rel = file.canonicalFile.relativeToOrNull(root.canonicalFile) ?: return Int.MAX_VALUE
        return rel.path.count { it == File.separatorChar }
    }

    private sealed interface ManifestParse {
        data class Ok(val manifest: BackupManifest) : ManifestParse
        object Empty : ManifestParse
        data class Invalid(val reason: String) : ManifestParse
    }

    private fun parseManifest(file: File): ManifestParse {
        val text = runCatching { file.readText() }.getOrNull()
            ?: return ManifestParse.Invalid("could not read manifest file")
        if (text.isBlank()) return ManifestParse.Empty
        return runCatching {
            ManifestParse.Ok(json.decodeFromString<BackupManifest>(text))
        }.getOrElse { t ->
            ManifestParse.Invalid(t.message ?: t.javaClass.simpleName)
        }
    }
}

/**
 * Result of [BackupArchive.extractBackupZip]. [manifestFile] is the manifest
 * found at the *shallowest* depth inside the archive, or null if none was seen.
 * [entryNames] lists every entry encountered in archive order (useful for
 * surfacing diagnostic detail when a restore fails).
 */
data class ExtractedBackup(
    val root: File,
    val manifestFile: File?,
    val entryNames: List<String>,
)

/**
 * Outcome of [BackupArchive.planRestore]. Either an actionable [Ok] (caller
 * proceeds to copy [dbSource] over the host DB and replay file directories
 * relative to [effectiveRoot]) or a [Failure] carrying a structured reason.
 */
sealed interface RestorePlan {
    data class Ok(
        val manifest: BackupManifest,
        val dbSource: File,
        val effectiveRoot: File,
    ) : RestorePlan

    data class Failure(val reason: FailureReason) : RestorePlan
}

/**
 * Structured failure detail. UI / HTTP layers convert these to user-facing
 * strings; tests inspect the variant directly so messages can evolve without
 * breaking expectations.
 */
sealed interface FailureReason {
    /** No `manifest.json` entry found anywhere in the archive. */
    data class MissingManifest(val entriesSeen: List<String>) : FailureReason

    /** Manifest file exists but is empty. */
    object EmptyManifest : FailureReason

    /** Manifest file exists but did not deserialize. */
    data class InvalidManifest(val reason: String) : FailureReason

    /** Manifest format version differs from [BackupManifest.CURRENT_FORMAT_VERSION]. */
    data class UnsupportedFormat(val formatVersion: Int) : FailureReason

    /** Archive's `dbVersion` exceeds the host's `SCHEMA_VERSION`. */
    data class BackupNewerThanHost(val backupDbVersion: Int, val hostDbVersion: Int) : FailureReason

    /** Manifest parsed but no SQLite database file was found in the archive. */
    data class MissingDatabase(val expectedFileName: String) : FailureReason
}
