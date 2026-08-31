package com.example.optimalx.data.litert

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test when [gemma-4-E4B-it.litertlm] is present (skipped otherwise).
 */
@RunWith(AndroidJUnit4::class)
class LitertLmSmokeInstrumentedTest {

    @Test
    fun runOneShotPrompt_whenModelFileExists() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelPath = LitertLmDefaults.firstExistingModelPath(context)
        if (modelPath == null) {
            println("LitertLmSmoke: no model on device — skipping")
            return@runBlocking
        }

        val result = LitertLmSmokeRunner().runOneShotPrompt(
            context = context,
            modelPath = modelPath,
        ).getOrThrow()

        assertTrue(result.responseText.isNotBlank())
        println(
            "LitertLmSmoke: init=${result.initMillis}ms infer=${result.inferenceMillis}ms " +
                "response=${result.responseText.take(120)}",
        )
    }
}
