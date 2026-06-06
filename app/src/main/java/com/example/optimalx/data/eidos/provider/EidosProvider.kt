package com.example.optimalx.data.eidos.provider

import com.example.optimalx.data.eidos.model.EidosRequest
import com.example.optimalx.data.eidos.model.EidosResponse

interface EidosProvider {
    suspend fun send(request: EidosRequest): EidosResponse
}
