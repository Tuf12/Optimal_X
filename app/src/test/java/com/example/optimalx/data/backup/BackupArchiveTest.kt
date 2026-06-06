package com.example.optimalx.data.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JVM tests for the pure archive core defined in [BackupArchive]. No Android
 * dependencies — runs in `./gradlew :app:testDebugUnitTest`.
 */
class BackupArchiveTest {

    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = File.createTempFile("optimalx-archive-test-", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    // ---------- happy path ----------

    @Test
    fun writeAndExtract_roundtripsAllPayloads() {
        val source = stagedSource(
            dbBytes = "SQLite format 3\u0000fake-db-bytes".toByteArray(),
            attachments = mapOf(
                "alpha.pdf" to "alpha-pdf-content".toByteArray(),
                "nested/beta.png" to byteArrayOf(0x1, 0x2, 0x3),
            ),
            workshop = mapOf(
                "11/index.html" to "<html></html>".toByteArray(),
                "11/script.js" to "console.log(1);".toByteArray(),
                "42/style.css" to "body{}".toByteArray(),
            ),
        )
        val manifest = BackupManifest(
            formatVersion = BackupManifest.CURRENT_FORMAT_VERSION,
            exportEpochMs = 1716800000000L,
            appVersionName = "1.0-test",
            dbVersion = 18,
            includesFiles = true,
        )

        val bytes = writeToBytes(manifest, source.dbFile, source.attachmentsDir, source.workshopDir)
        val extracted = BackupArchive.extractBackupZip(ByteArrayInputStream(bytes), File(tempRoot, "extract"))

        assertNotNull("manifest entry should be located", extracted.manifestFile)
        assertTrue("entries should include manifest", extracted.entryNames.contains("manifest.json"))
        assertTrue(
            "entries should include db",
            extracted.entryNames.any { it == "database/${source.dbFile.name}" },
        )

        val plan = BackupArchive.planRestore(extracted, hostSchemaVersion = 18, dbFileName = source.dbFile.name)
        plan as RestorePlan.Ok
        assertEquals(manifest, plan.manifest)
        assertEquals(extracted.root, plan.effectiveRoot)
        assertTrue(plan.dbSource.isFile)
        assertEquals(source.dbFile.readBytes().toList(), plan.dbSource.readBytes().toList())

        val attachments = BackupArchive.attachmentsRoot(plan)
        assertEquals(
            "alpha-pdf-content",
            File(attachments, "alpha.pdf").readText(),
        )
        assertEquals(
            listOf<Byte>(0x1, 0x2, 0x3),
            File(attachments, "nested/beta.png").readBytes().toList(),
        )

        val workshop = BackupArchive.workshopRoot(plan)
        assertEquals("<html></html>", File(workshop, "11/index.html").readText())
        assertEquals("console.log(1);", File(workshop, "11/script.js").readText())
        assertEquals("body{}", File(workshop, "42/style.css").readText())
    }

    @Test
    fun writeAndExtract_includesOptionalDumpEditPayload() {
        val source = stagedSource(
            dbBytes = "SQLite format 3\u0000fake-db".toByteArray(),
            attachments = emptyMap(),
            workshop = emptyMap(),
        )
        val dumpEditJson = """
            {"content":"<p>scratch</p>","aiLocked":true,"aiBlind":false}
        """.trimIndent()
        val manifest = BackupManifest(formatVersion = 1, dbVersion = 18)
        val bytes = writeToBytes(manifest, source.dbFile, null, null, dumpEditJson)
        val extracted = BackupArchive.extractBackupZip(ByteArrayInputStream(bytes), File(tempRoot, "extract"))
        val plan = BackupArchive.planRestore(extracted, 18, source.dbFile.name) as RestorePlan.Ok

        assertTrue(extracted.entryNames.contains(BackupArchive.DUMP_EDIT_ENTRY))
        val dumpEditFile = BackupArchive.dumpEditFile(plan)
        assertNotNull(dumpEditFile)
        val payload = BackupArchive.parseDumpEditPayload(dumpEditFile!!)
        assertEquals("<p>scratch</p>", payload?.content)
        assertEquals(true, payload?.aiLocked)
        assertEquals(false, payload?.aiBlind)
    }

    @Test
    fun writeAndExtract_acceptsMissingAttachmentsAndWorkshop() {
        val source = stagedSource(
            dbBytes = "SQLite format 3\u0000".toByteArray(),
            attachments = emptyMap(),
            workshop = emptyMap(),
        )
        val manifest = BackupManifest(formatVersion = 1, dbVersion = 18)

        val bytes = writeToBytes(manifest, source.dbFile, attachmentsDir = null, workshopDir = null)
        val extracted = BackupArchive.extractBackupZip(ByteArrayInputStream(bytes), File(tempRoot, "extract"))
        val plan = BackupArchive.planRestore(extracted, 18, source.dbFile.name)
        assertTrue(plan is RestorePlan.Ok)
    }

    // ---------- wrapped archive ----------

    @Test
    fun planRestore_findsManifestInsideWrapperFolder() {
        val zipBytes = buildZip {
            entry("wrapper/manifest.json", manifestJson(formatVersion = 1, dbVersion = 18))
            entry("wrapper/database/optimalx.db", "SQLite format 3\u0000body".toByteArray())
            entry("wrapper/files/optimalx_files/note.txt", "hello".toByteArray())
        }

        val extracted = BackupArchive.extractBackupZip(
            ByteArrayInputStream(zipBytes),
            File(tempRoot, "extract"),
        )
        val plan = BackupArchive.planRestore(extracted, hostSchemaVersion = 18, dbFileName = "optimalx.db")
        plan as RestorePlan.Ok
        assertEquals("optimalx.db", plan.dbSource.name)
        assertTrue(plan.dbSource.path.contains("wrapper"))
        assertTrue(
            "effective root should be the wrapper directory",
            plan.effectiveRoot.path.endsWith("wrapper"),
        )
    }

    @Test
    fun planRestore_prefersShallowestManifestWhenMultiplePresent() {
        val zipBytes = buildZip {
            entry("manifest.json", manifestJson(formatVersion = 1, dbVersion = 18))
            entry("nested/manifest.json", manifestJson(formatVersion = 1, dbVersion = 5))
            entry("database/optimalx.db", "SQLite format 3\u0000".toByteArray())
        }
        val extracted = BackupArchive.extractBackupZip(
            ByteArrayInputStream(zipBytes),
            File(tempRoot, "extract"),
        )
        val plan = BackupArchive.planRestore(extracted, hostSchemaVersion = 18, dbFileName = "optimalx.db")
        plan as RestorePlan.Ok
        assertEquals(18, plan.manifest.dbVersion)
    }

    // ---------- failure modes ----------

    @Test
    fun planRestore_missingManifest_listsEntriesSeen() {
        val zipBytes = buildZip {
            entry("database/optimalx.db", "SQLite format 3\u0000body".toByteArray())
            entry("files/optimalx_files/x.txt", "x".toByteArray())
        }
        val extracted = BackupArchive.extractBackupZip(
            ByteArrayInputStream(zipBytes),
            File(tempRoot, "extract"),
        )
        val plan = BackupArchive.planRestore(extracted, 18, "optimalx.db")
        plan as RestorePlan.Failure
        val reason = plan.reason as FailureReason.MissingManifest
        assertEquals(2, reason.entriesSeen.size)
        assertTrue(reason.entriesSeen.contains("database/optimalx.db"))
    }

    @Test
    fun planRestore_emptyManifest_reportsEmpty() {
        val zipBytes = buildZip {
            entry("manifest.json", "".toByteArray())
            entry("database/optimalx.db", "SQLite format 3\u0000".toByteArray())
        }
        val extracted = BackupArchive.extractBackupZip(
            ByteArrayInputStream(zipBytes),
            File(tempRoot, "extract"),
        )
        val plan = BackupArchive.planRestore(extracted, 18, "optimalx.db")
        plan as RestorePlan.Failure
        assertEquals(FailureReason.EmptyManifest, plan.reason)
    }

    @Test
    fun planRestore_invalidManifest_carriesReason() {
        val zipBytes = buildZip {
            entry("manifest.json", "not json".toByteArray())
            entry("database/optimalx.db", "SQLite format 3\u0000".toByteArray())
        }
        val extracted = BackupArchive.extractBackupZip(
            ByteArrayInputStream(zipBytes),
            File(tempRoot, "extract"),
        )
        val plan = BackupArchive.planRestore(extracted, 18, "optimalx.db")
        plan as RestorePlan.Failure
        val reason = plan.reason as FailureReason.InvalidManifest
        assertTrue("reason should be non-empty", reason.reason.isNotBlank())
    }

    @Test
    fun planRestore_unsupportedFormat_reported() {
        val zipBytes = buildZip {
            entry("manifest.json", manifestJson(formatVersion = 2, dbVersion = 18))
            entry("database/optimalx.db", "SQLite format 3\u0000".toByteArray())
        }
        val extracted = BackupArchive.extractBackupZip(
            ByteArrayInputStream(zipBytes),
            File(tempRoot, "extract"),
        )
        val plan = BackupArchive.planRestore(extracted, 18, "optimalx.db")
        plan as RestorePlan.Failure
        assertEquals(FailureReason.UnsupportedFormat(2), plan.reason)
    }

    @Test
    fun planRestore_backupNewerThanHost_reported() {
        val zipBytes = buildZip {
            entry("manifest.json", manifestJson(formatVersion = 1, dbVersion = 99))
            entry("database/optimalx.db", "SQLite format 3\u0000".toByteArray())
        }
        val extracted = BackupArchive.extractBackupZip(
            ByteArrayInputStream(zipBytes),
            File(tempRoot, "extract"),
        )
        val plan = BackupArchive.planRestore(extracted, hostSchemaVersion = 18, dbFileName = "optimalx.db")
        plan as RestorePlan.Failure
        val reason = plan.reason as FailureReason.BackupNewerThanHost
        assertEquals(99, reason.backupDbVersion)
        assertEquals(18, reason.hostDbVersion)
    }

    @Test
    fun planRestore_missingDatabase_reported() {
        val zipBytes = buildZip {
            entry("manifest.json", manifestJson(formatVersion = 1, dbVersion = 18))
        }
        val extracted = BackupArchive.extractBackupZip(
            ByteArrayInputStream(zipBytes),
            File(tempRoot, "extract"),
        )
        val plan = BackupArchive.planRestore(extracted, 18, "optimalx.db")
        plan as RestorePlan.Failure
        assertEquals(FailureReason.MissingDatabase("optimalx.db"), plan.reason)
    }

    // ---------- compatibility ----------

    @Test
    fun parseManifest_acceptsUnknownFields() {
        val zipBytes = buildZip {
            entry(
                "manifest.json",
                """
                {
                  "formatVersion": 1,
                  "exportEpochMs": 5,
                  "appVersionName": "x",
                  "dbVersion": 18,
                  "includesFiles": true,
                  "futureField": "ok",
                  "anotherFuture": 42
                }
                """.trimIndent().toByteArray(),
            )
            entry("database/optimalx.db", "SQLite format 3\u0000".toByteArray())
        }
        val extracted = BackupArchive.extractBackupZip(
            ByteArrayInputStream(zipBytes),
            File(tempRoot, "extract"),
        )
        val plan = BackupArchive.planRestore(extracted, 18, "optimalx.db")
        plan as RestorePlan.Ok
        assertEquals(18, plan.manifest.dbVersion)
        assertEquals("x", plan.manifest.appVersionName)
    }

    @Test
    fun parseManifest_fillsDefaultsForMissingOptionalFields() {
        val zipBytes = buildZip {
            entry("manifest.json", """{"formatVersion":1}""".toByteArray())
            entry("database/optimalx.db", "SQLite format 3\u0000".toByteArray())
        }
        val extracted = BackupArchive.extractBackupZip(
            ByteArrayInputStream(zipBytes),
            File(tempRoot, "extract"),
        )
        val plan = BackupArchive.planRestore(extracted, hostSchemaVersion = 18, dbFileName = "optimalx.db")
        plan as RestorePlan.Ok
        assertEquals(1, plan.manifest.formatVersion)
        assertEquals(0L, plan.manifest.exportEpochMs)
        assertEquals("", plan.manifest.appVersionName)
        assertEquals(0, plan.manifest.dbVersion)
        assertEquals(false, plan.manifest.includesFiles)
    }

    // ---------- security ----------

    @Test
    fun extractZip_rejectsZipSlip() {
        val zipBytes = buildZip {
            entry("../escape.txt", "evil".toByteArray())
        }
        var thrown: Throwable? = null
        try {
            BackupArchive.extractBackupZip(
                ByteArrayInputStream(zipBytes),
                File(tempRoot, "extract"),
            )
        } catch (t: IllegalStateException) {
            thrown = t
        }
        assertNotNull("zip-slip attempt must throw", thrown)
        // Confirm no file was written outside destDir.
        assertNull(File(tempRoot, "escape.txt").parentFile?.listFiles()?.firstOrNull { it.name == "escape.txt" })
    }

    // ---------- helpers ----------

    private data class StagedSource(
        val dbFile: File,
        val attachmentsDir: File?,
        val workshopDir: File?,
    )

    private fun stagedSource(
        dbBytes: ByteArray,
        attachments: Map<String, ByteArray>,
        workshop: Map<String, ByteArray>,
    ): StagedSource {
        val sourceRoot = File(tempRoot, "source").apply { mkdirs() }
        val dbFile = File(sourceRoot, "optimalx.db").apply { writeBytes(dbBytes) }
        val attachmentsDir = if (attachments.isEmpty()) {
            null
        } else {
            File(sourceRoot, "optimalx_files").also { dir ->
                dir.mkdirs()
                attachments.forEach { (rel, bytes) ->
                    val target = File(dir, rel)
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                }
            }
        }
        val workshopDir = if (workshop.isEmpty()) {
            null
        } else {
            File(sourceRoot, "workshop").also { dir ->
                dir.mkdirs()
                workshop.forEach { (rel, bytes) ->
                    val target = File(dir, rel)
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                }
            }
        }
        return StagedSource(dbFile, attachmentsDir, workshopDir)
    }

    private fun writeToBytes(
        manifest: BackupManifest,
        dbFile: File,
        attachmentsDir: File?,
        workshopDir: File?,
        dumpEditJson: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        BackupArchive.writeBackupZip(out, manifest, dbFile, attachmentsDir, workshopDir, dumpEditJson)
        return out.toByteArray()
    }

    private fun manifestJson(formatVersion: Int, dbVersion: Int): ByteArray =
        """
        {
          "formatVersion": $formatVersion,
          "exportEpochMs": 1,
          "appVersionName": "test",
          "dbVersion": $dbVersion,
          "includesFiles": true
        }
        """.trimIndent().toByteArray()

    private class ZipBuilder {
        val bytes = ByteArrayOutputStream()
        val zip = ZipOutputStream(bytes)
        fun entry(path: String, content: ByteArray) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(content)
            zip.closeEntry()
        }
        fun finish(): ByteArray {
            zip.close()
            return bytes.toByteArray()
        }
    }

    private fun buildZip(block: ZipBuilder.() -> Unit): ByteArray {
        val builder = ZipBuilder()
        builder.block()
        return builder.finish()
    }
}
