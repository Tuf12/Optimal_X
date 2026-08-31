package com.example.optimalx.data.imagestudio

import androidx.room.Embedded
import com.example.optimalx.data.model.FileReference

data class FileReferenceWithFolderLabels(
    @Embedded val ref: FileReference,
    val subfolderName: String,
    val parentFolderName: String,
)
