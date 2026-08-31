package com.example.optimalx.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPairingUriTest {

    @Test
    fun buildAndParse_hostPortForm() {
        val uri = SyncPairingUri.build("192.168.1.10", 7373, "secret-token")
        val endpoint = SyncPairingUri.parse(uri)
        assertNotNull(endpoint)
        assertEquals("192.168.1.10", endpoint!!.host)
        assertEquals(7373, endpoint.port)
        assertEquals("secret-token", endpoint.token)
    }

    @Test
    fun parse_rejectsMissingToken() {
        assertNull(SyncPairingUri.parse("optimalx-sync://192.168.1.10:7373"))
    }

    @Test
    fun normalizeEndpoint_splitsHostPort() {
        val endpoint = SyncPairingUri.normalizeEndpoint("10.0.0.5:7373", 8080, "tok")
        assertEquals("10.0.0.5", endpoint.host)
        assertEquals(7373, endpoint.port)
        assertEquals("tok", endpoint.token)
    }

    @Test
    fun endpoint_urls_useSyncApiPath() {
        val endpoint = SyncEndpoint("localhost", 7373, "t")
        assertEquals("http://localhost:7373/api/v1/sync/status", endpoint.statusUrl())
        assertEquals("http://localhost:7373/api/v1/sync/push", endpoint.pushUrl())
    }

    @Test
    fun endpoint_urls_useFilesApiPath() {
        val endpoint = SyncEndpoint("localhost", 7373, "t")
        assertEquals(
            "http://localhost:7373/api/v1/files/request/gid-1?kind=attachment",
            endpoint.filesRequestUrl("gid-1", "attachment"),
        )
        assertEquals(
            "http://localhost:7373/api/v1/files/request/gid-1?kind=workshop&path=index.html",
            endpoint.filesRequestUrl("gid-1", "workshop", "index.html"),
        )
        assertEquals(
            "http://localhost:7373/api/v1/files/request/gid-1?kind=mobile_workshop_backup&path=script.js",
            endpoint.filesRequestUrl("gid-1", SyncFileApi.KIND_MOBILE_WORKSHOP_BACKUP, "script.js"),
        )
        assertEquals("http://localhost:7373/api/v1/files/push/gid-1", endpoint.filesPushUrl("gid-1"))
    }
}
