package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.data.eidos.prefetch.EidosPrefetchPolicy

/** Which contextual resources the composer may inline for a scope profile. */
data class EidosContextPolicy(
    val injectDailyMemory: Boolean = false,
    val prefetchPolicy: EidosPrefetchPolicy = EidosPrefetchPolicy.DISABLED,
) {
    companion object {
        val CHAT_WITH_DAILY_MEMORY = EidosContextPolicy(
            injectDailyMemory = true,
            prefetchPolicy = EidosPrefetchPolicy.MAIN_CHAT,
        )
        val WIDGET_WITH_PREFETCH = EidosContextPolicy(
            injectDailyMemory = false,
            prefetchPolicy = EidosPrefetchPolicy.WIDGET_MEMORY,
        )
        val WORKSHOP_CHAT = EidosContextPolicy(
            prefetchPolicy = EidosPrefetchPolicy.WORKSHOP_CHAT,
        )
        val WORKSHOP_BUILD = EidosContextPolicy(
            prefetchPolicy = EidosPrefetchPolicy.WORKSHOP_BUILD,
        )
        val WORKSHOP_INTAKE = EidosContextPolicy(
            prefetchPolicy = EidosPrefetchPolicy.WORKSHOP_INTAKE,
        )
        val DEFAULT = EidosContextPolicy()
    }
}
