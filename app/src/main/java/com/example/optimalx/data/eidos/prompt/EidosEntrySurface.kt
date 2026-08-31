package com.example.optimalx.data.eidos.prompt

import com.example.optimalx.ui.eidos.EidosChatEntrySurface

/** Where the user invoked Eidos for this send (router input dimension). */
enum class EidosEntrySurface {
    APP_CHAT,
    WIDGET_ASK,
    WIDGET_CHAT,
    WIDGET_QUICK_NOTE,
    BACKGROUND_WORKER,
    INTERNAL,
}

fun EidosChatEntrySurface.toPromptEntrySurface(): EidosEntrySurface = when (this) {
    EidosChatEntrySurface.MAIN_APP -> EidosEntrySurface.APP_CHAT
    EidosChatEntrySurface.WIDGET -> EidosEntrySurface.WIDGET_CHAT
}
