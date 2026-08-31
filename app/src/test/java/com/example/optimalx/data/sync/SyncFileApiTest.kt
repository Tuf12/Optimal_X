package com.example.optimalx.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFileApiTest {

    @Test
    fun formatFileHttpError_mapsKnownCodes() {
        val api = SyncFileApi()
        val unauthorized = api.formatFileHttpErrorForTest(401, """{"error":"unauthorized"}""")
        assertTrue(unauthorized.contains("401"))
        assertTrue(unauthorized.contains("bearer token"))

        val notFound = api.formatFileHttpErrorForTest(404, """{"error":"not_found"}""")
        assertTrue(notFound.contains("Tier 1 Push") || notFound.contains("Pull"))

        val attachmentNotFound = api.formatFileHttpErrorForTest(
            404,
            """{"error":"not_found"}""",
            attachmentFileName = "report.pdf",
        )
        assertTrue(attachmentNotFound.contains("report.pdf"))

        val missingBytes = api.formatFileHttpErrorForTest(404, """{"error":"file_not_found"}""")
        assertTrue(missingBytes.contains("bytes are missing"))

        val tooLarge = api.formatFileHttpErrorForTest(413, """{"error":"payload_too_large"}""")
        assertTrue(tooLarge.contains("100 MB"))

        val missingFile = api.formatFileHttpErrorForTest(
            400,
            """{"error":"missing_file"}""",
            attachmentFileName = "report.pdf",
        )
        assertTrue(missingFile.contains("report.pdf"))
        assertTrue(missingFile.contains("did not receive bytes"))
    }

    private fun SyncFileApi.formatFileHttpErrorForTest(
        code: Int,
        body: String,
        workshopRelativePath: String? = null,
        attachmentFileName: String? = null,
    ): String {
        val method = SyncFileApi::class.java.getDeclaredMethod(
            "formatFileHttpError",
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(this, code, body, workshopRelativePath, attachmentFileName) as String
    }
}
