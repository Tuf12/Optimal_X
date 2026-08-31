package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "semantic_vectors",
    indices = [
        Index(value = ["sourceType", "sourceId"], unique = true),
        Index(value = ["updatedAt"]),
    ],
)
data class SemanticVector(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceType: String,
    val sourceId: Long,
    val embeddingBlob: ByteArray,
    val updatedAt: Long = System.currentTimeMillis(),
)
