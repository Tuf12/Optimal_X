package com.example.optimalx.data.backup

import kotlinx.serialization.Serializable

/** Serialized as `preferences/dump_edit.json` inside OptimalX snapshot zips. */
@Serializable
data class DumpEditBackupPayload(
    val content: String = "",
    val aiLocked: Boolean = false,
    val aiBlind: Boolean = false,
)
