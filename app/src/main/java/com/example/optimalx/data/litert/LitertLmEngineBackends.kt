package com.example.optimalx.data.litert

import com.google.ai.edge.litertlm.Backend

/**
 * Gemma 4 LiteRT-LM requires CPU for audio subgraphs even when the main decoder runs on GPU.
 * Gallery uses the same split (GPU text/vision + CPU audio).
 */
object LitertLmEngineBackends {

    data class Resolved(
        val main: Backend,
        val vision: Backend,
        val audio: Backend,
    )

    fun resolve(backend: LitertLmBackend): Resolved {
        val main = when (backend) {
            LitertLmBackend.GPU -> Backend.GPU()
            LitertLmBackend.CPU -> Backend.CPU()
        }
        val audio = Backend.CPU()
        return Resolved(main = main, vision = main, audio = audio)
    }
}
