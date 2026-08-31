package com.example.optimalx.data.eidos

/** Scope keys for persisted panel runtime state. */
object PanelStateScope {
    const val GLOBAL = "global"

    fun forHostSubfolder(hostSubfolderId: Long): String = "subfolder:$hostSubfolderId"
}
