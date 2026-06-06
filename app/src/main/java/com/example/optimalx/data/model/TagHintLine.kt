package com.example.optimalx.data.model

// ON HOLD — Room row for Eidos Index (tag_hint_lines). Inactive while EidosIndexFeature is off.

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tag_hint_lines",
    indices = [
        Index(value = ["ref"], unique = true),
        Index(value = ["objectType"]),
        Index(value = ["scopeType"]),
        Index(value = ["rootBranch"]),
        Index(value = ["tag"]),
        Index(value = ["date"]),
    ],
)
data class TagHintLine(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ref: String,
    val objectType: String,
    val scopeType: String = "none",
    val scopeId: String? = null,
    val parentRef: String? = null,
    val rootBranch: String,
    /** Short semantic label (topic/category, typically 1–4 words). */
    val tag: String,
    /** Brief description of what the object is about. */
    val hint: String,
    /** Human-readable name of the indexed object (folder name, file name, chat title, etc.). */
    val objectName: String,
    val parentFolderName: String? = null,
    val subfolderName: String? = null,
    val date: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
