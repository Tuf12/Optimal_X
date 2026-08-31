package com.example.optimalx.data.sync

import com.example.optimalx.data.model.Note
import com.example.optimalx.data.model.PanelState
import java.security.MessageDigest

object SyncContentHash {

    fun sha256Hex(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun noteContentHash(content: String): String = sha256Hex(content)

    fun panelStateContentHash(stateJson: String): String = sha256Hex(stateJson)

    fun Note.withSyncFields(
        content: String = this.content,
        updatedAt: Long = System.currentTimeMillis(),
        transform: (Note) -> Note = { it },
    ): Note {
        val base = transform(copy(content = content, updatedAt = updatedAt))
        return base.copy(contentHash = noteContentHash(base.content))
    }

    fun PanelState.withSyncFields(
        stateJson: String = this.stateJson,
        updatedAt: Long = System.currentTimeMillis(),
    ): PanelState = copy(
        stateJson = stateJson,
        updatedAt = updatedAt,
        contentHash = panelStateContentHash(stateJson),
    )
}
