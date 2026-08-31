package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EidosInternalSendTest {

    @Test
    fun summaryTextOrNull_returnsTrimmedTextOnSuccess() {
        val response = EidosResponse(textResponse = "  hello summary  ")
        assertEquals("hello summary", response.summaryTextOrNull())
    }

    @Test
    fun summaryTextOrNull_returnsNullOnTransportFailure() {
        val response = EidosResponse(
            textResponse = "Kimi timed out at https://api.moonshot.ai",
            transportFailure = true,
        )
        assertNull(response.summaryTextOrNull())
    }

    @Test
    fun summaryTextOrNull_returnsNullOnBlank() {
        val response = EidosResponse(textResponse = "   ")
        assertNull(response.summaryTextOrNull())
    }
}
