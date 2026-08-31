package com.example.optimalx.data.litert

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LitertLmWarmPoolCoordinatorTest {

    @Test
    fun trimMemory_doesNotUnloadOnMinimizeOrLru() {
        assertFalse(shouldReleaseLitertEngineOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(shouldReleaseLitertEngineOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertFalse(shouldReleaseLitertEngineOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertFalse(shouldReleaseLitertEngineOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
        assertFalse(shouldReleaseLitertEngineOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertFalse(shouldReleaseLitertEngineOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
    }

    @Test
    fun trimMemory_doesNotUnloadOnLowMemoryLevel() {
        // onLowMemory() must not be mapped to RUNNING_CRITICAL — that caused unload on minimize.
        assertFalse(shouldReleaseLitertEngineOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun trimMemory_unloadsOnlyOnRunningCritical() {
        assertTrue(
            shouldReleaseLitertEngineOnTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL),
        )
    }
}
