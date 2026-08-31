package com.example.optimalx.data.eidos

import com.example.optimalx.data.eidos.model.EidosResponse

/**
 * Returns assistant text suitable for persisting as an internal summary/digest, or null when the
 * provider transport failed or returned blank output.
 */
fun EidosResponse.summaryTextOrNull(): String? {
    if (transportFailure) return null
    return textResponse.trim().takeIf { it.isNotEmpty() }
}
