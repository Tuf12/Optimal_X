package com.example.optimalx.data.eidos

import kotlinx.serialization.Serializable

@Serializable
data class EidosNavigationTarget(
    val kind: String,
    val label: String,
    val subfolderId: Long? = null,
    val parentFolderId: Long? = null,
    val parentName: String? = null,
    val path: String? = null,
)
