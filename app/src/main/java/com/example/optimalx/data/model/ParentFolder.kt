package com.example.optimalx.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.optimalx.data.sync.SyncGlobalIds

@Entity(
    tableName = "parent_folders",
    indices = [Index(value = ["globalId"], unique = true)],
)
data class ParentFolder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val deletedAt: Long? = null,
    val isSystemFolder: Boolean = false,
    val globalId: String = SyncGlobalIds.newGlobalId(),
    val originDeviceId: String? = null,
)
