package com.example.optimalx.data.eidos.provider

import com.example.optimalx.data.eidos.model.EidosRequest
import com.example.optimalx.data.eidos.model.EidosResponse

/**
 * Optional hook on [EidosRequest] — providers call after building the outbound JSON body
 * and receiving a parsed [EidosResponse].
 */
internal suspend fun EidosRequest.emitProviderExchange(
    requestBodyJson: String,
    response: EidosResponse,
) {
    onProviderExchange?.invoke(requestBodyJson, response)
}
