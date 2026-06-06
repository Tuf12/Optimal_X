package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "parent_folders")
data class ParentFolder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val deletedAt: Long? = null,
    val isSystemFolder: Boolean = false,
)
