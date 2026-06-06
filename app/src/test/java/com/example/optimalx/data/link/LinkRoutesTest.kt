package com.example.optimalx.data.link

import com.example.optimalx.data.backup.BackupArchive
import com.example.optimalx.data.backup.BackupManifest
import com.example.optimalx.data.backup.BackupResult
import com.example.optimalx.data.backup.RestorePlan
import com.example.optimalx.data.backup.RestoreValidation
import com.example.optimalx.data.backup.SnapshotStats
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Integration tests for the OptimalX Link HTTP surface. Drives [installRoutes]
 * via Ktor `testApplication { }` with fake stats + snapshot writer lambdas so
 * the tests run on the JVM without Android.
 */
class LinkRoutesTest {

    private val token = "test-token-abc123"
    private val appVersionName = "1.2.3"

    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = File.createTempFile("optimalx-link-test-", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    /**
     * Default-arg helper around [installRoutes] that fills in the restore
     * plumbing with no-op fakes. Tests that need to exercise restore
     * behavior live in [LinkRestoreRoutesTest] and supply their own.
     */
    private fun Application.installTestRoutes(
        tokenSupplier: () -> String = { token },
        statsProvider: () -> SnapshotStats = { SnapshotStats(0L, 0, 0) },
        snapshotWriter: (OutputStream) -> Unit = { },
    ) {
        val restoreRoot = File(tempRoot, "restore").apply { mkdirs() }
        installRoutes(
            appVersionName = appVersionName,
            tokenSupplier = tokenSupplier,
            onActivity = {},
            statsProvider = statsProvider,
            snapshotWriter = snapshotWriter,
            restoreJobs = RestoreJobRegistry(),
            restoreRoot = restoreRoot,
            restoreValidator = { _: InputStream, _: File ->
                RestoreValidation.Failed(message = "stub validator")
            },
            restoreApplier = { _: RestorePlan.Ok ->
                BackupResult.failure("stub applier")
            },
        )
    }

    @Test
    fun status_withoutBearer_returns401() = testApplication {
        application {
            installTestRoutes()
        }
        val response = client.get("/v1/status")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun status_withWrongBearer_returns401() = testApplication {
        application {
            installTestRoutes()
        }
        val response = client.get("/v1/status") { bearerAuth("wrong") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun status_withValidBearer_returnsExpectedShape() = testApplication {
        application {
            installTestRoutes(
                statsProvider = {
                    SnapshotStats(
                        dbFileSize = 4096L,
                        attachmentCount = 7,
                        workshopProjectCount = 3,
                    )
                },
            )
        }
        val configuredClient = createClient {
            install(ContentNegotiation) { json() }
        }
        val response = configuredClient.get("/v1/status") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)

        val body: StatusResponse = response.body()
        assertEquals(appVersionName, body.appVersion)
        assertEquals(BackupManifest.CURRENT_FORMAT_VERSION, body.formatVersion)
        assertEquals(LINK_PROTOCOL_VERSION, body.linkVersion)
        assertEquals(4096L, body.dbFileSize)
        assertEquals(7, body.attachmentCount)
        assertEquals(3, body.workshopProjectCount)
        // dbVersion is pulled from AppDatabase.SCHEMA_VERSION; just assert it's
        // a positive integer rather than couple the test to the current value.
        assertTrue("dbVersion must be >= 1, was ${body.dbVersion}", body.dbVersion >= 1)
    }

    @Test
    fun status_emptyTokenAlwaysRejects() = testApplication {
        // While the real server is OFF the token field is "". A client sending
        // an empty bearer must NOT be accepted.
        application {
            installTestRoutes(tokenSupplier = { "" })
        }
        val response = client.get("/v1/status") { bearerAuth("") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun bundlesFull_withoutBearer_returns401() = testApplication {
        application {
            installTestRoutes(snapshotWriter = { fakeSnapshot(it) })
        }
        val response = client.get("/v1/bundles/full")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun bundlesFull_withBearer_streamsParseableZip() = testApplication {
        application {
            installTestRoutes(snapshotWriter = { fakeSnapshot(it) })
        }
        val response = client.get("/v1/bundles/full") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)

        val bytes = response.bodyAsBytes()
        // ZIP local file header magic = "PK\u0003\u0004"
        assertTrue("body must start with PK zip magic", bytes.size >= 4)
        assertEquals(0x50.toByte(), bytes[0])
        assertEquals(0x4B.toByte(), bytes[1])

        val extracted = BackupArchive.extractBackupZip(
            input = ByteArrayInputStream(bytes),
            destDir = File(tempRoot, "extract"),
        )
        // Manifest must round-trip with our test values. We route through
        // `planRestore` since `ExtractedBackup` exposes the manifest file only;
        // the parsed `BackupManifest` is on the plan.
        val plan = BackupArchive.planRestore(
            extracted = extracted,
            hostSchemaVersion = 999_999,
            dbFileName = "optimalx.db",
        )
        assertTrue(
            "expected RestorePlan.Ok, got $plan",
            plan is RestorePlan.Ok,
        )
        plan as RestorePlan.Ok
        assertEquals(BackupManifest.CURRENT_FORMAT_VERSION, plan.manifest.formatVersion)
        assertEquals(appVersionName, plan.manifest.appVersionName)
        // Database entry must be present.
        assertTrue(
            "expected database file inside extracted backup",
            extracted.entryNames.any { it.startsWith(BackupArchive.DB_DIR_PREFIX) },
        )
    }

    @Test
    fun bearerToken_canBeRotatedAtRuntime() = testApplication {
        // Simulates `LinkServer.regenerateToken()` while the engine is running:
        // tokenSupplier changes value across requests.
        var activeToken = "old-token"
        application {
            installTestRoutes(tokenSupplier = { activeToken })
        }
        val first = client.get("/v1/status") { bearerAuth(activeToken) }
        assertEquals(HttpStatusCode.OK, first.status)

        activeToken = "new-token"
        val staleAuth = client.get("/v1/status") { bearerAuth("old-token") }
        assertEquals(HttpStatusCode.Unauthorized, staleAuth.status)

        val freshAuth = client.get("/v1/status") { bearerAuth("new-token") }
        assertEquals(HttpStatusCode.OK, freshAuth.status)
    }

    /**
     * Writes a minimal but real OptimalX backup archive to [out] so the bundle
     * endpoint tests can verify the bytes parse as a snapshot. We use a fake
     * database payload because the snapshot writer in production reads from a
     * live AppDatabase, which is unavailable in JVM tests.
     */
    private fun fakeSnapshot(out: OutputStream) {
        val dbBytes = ByteArray(64).also { java.util.Random(42L).nextBytes(it) }
        // Filename must match the host DB name so planRestore can locate it
        // when the archive is unpacked.
        val dbFile = File(tempRoot, "optimalx.db").apply { writeBytes(dbBytes) }
        BackupArchive.writeBackupZip(
            out = out,
            manifest = BackupManifest(
                formatVersion = BackupManifest.CURRENT_FORMAT_VERSION,
                exportEpochMs = 1_700_000_000_000L,
                appVersionName = appVersionName,
                dbVersion = 18,
                includesFiles = false,
            ),
            dbFile = dbFile,
            attachmentsDir = null,
            workshopDir = null,
        )
    }

    @Suppress("unused")
    private val ensureJsonSymbolReachable = Json
}
