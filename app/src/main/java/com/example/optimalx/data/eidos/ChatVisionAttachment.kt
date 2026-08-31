package com.example.optimalx.data.eidos

import kotlinx.serialization.Serializable

/** Chat composer image attach. Same JSON shape as desktop `image_attachment_json`. */
@Serializable
data class ChatVisionAttachment(
    val fileName: String = "",
    val mimeType: String = "image/png",
    val storedName: String = "",
)
