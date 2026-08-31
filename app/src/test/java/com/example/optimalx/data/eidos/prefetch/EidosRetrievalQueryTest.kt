package com.example.optimalx.data.eidos.prefetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidosRetrievalQueryTest {

    @Test
    fun shouldPrefetch_rejectsEmptyAndGreetings() {
        assertFalse(EidosRetrievalQuery.shouldPrefetch(""))
        assertFalse(EidosRetrievalQuery.shouldPrefetch("hi"))
        assertFalse(EidosRetrievalQuery.shouldPrefetch("thanks!"))
        assertFalse(EidosRetrievalQuery.shouldPrefetch("ok"))
    }

    @Test
    fun shouldPrefetch_rejectsProbesAndShortMessages() {
        assertFalse(EidosRetrievalQuery.shouldPrefetch("test"))
        assertFalse(EidosRetrievalQuery.shouldPrefetch("testing"))
        assertFalse(EidosRetrievalQuery.shouldPrefetch("ping"))
        assertFalse(EidosRetrievalQuery.shouldPrefetch("fix button"))
    }

    @Test
    fun shouldPrefetch_acceptsSubstantiveMessage() {
        assertTrue(EidosRetrievalQuery.shouldPrefetch("What did we decide about the tile estimate?"))
        assertTrue(EidosRetrievalQuery.shouldPrefetch("I'm in Chicago and prefer metric units"))
    }

    @Test
    fun build_returnsTrimmedUserMessage() {
        assertEquals(
            "Continue from earlier",
            EidosRetrievalQuery.build("  Continue from earlier  "),
        )
    }
}
