package com.example.optimalx.ui.folders

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Memory
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.optimalx.data.db.SystemFolderNames

/**
 * Visual treatment for known system folders on parent/subfolder grids.
 * [caption] is shown below the icon; it stays stable even if the DB name differs slightly.
 */
data class SystemFolderBranding(
    val icon: ImageVector,
    val caption: String,
)

/**
 * Returns branding for [folderName] when it is a system folder that should stand out in the folder UI.
 */
fun systemFolderBrandingForName(folderName: String): SystemFolderBranding? = when (folderName) {
    SystemFolderNames.QUICK_NOTES -> SystemFolderBranding(
        icon = Icons.AutoMirrored.Filled.StickyNote2,
        caption = "Quick Notes",
    )
    SystemFolderNames.EIDOS_CHATS -> SystemFolderBranding(
        icon = Icons.Filled.ChatBubbleOutline,
        caption = "Chats",
    )
    SystemFolderNames.EIDOS_REASONING -> SystemFolderBranding(
        icon = Icons.Filled.AccountTree,
        caption = "Reasoning",
    )
    SystemFolderNames.CHATS_SUBFOLDER -> SystemFolderBranding(
        icon = Icons.Filled.ChatBubbleOutline,
        caption = "Chats",
    )
    SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER,
    "__memory_cache__",
    -> SystemFolderBranding(
        icon = Icons.Filled.Memory,
        caption = "Memory cache",
    )
    SystemFolderNames.PARENT_REASONING_SUBFOLDER -> SystemFolderBranding(
        icon = Icons.Filled.AccountTree,
        caption = "Reasoning",
    )
    SystemFolderNames.PANEL_WORKSHOP -> SystemFolderBranding(
        icon = Icons.Filled.Construction,
        caption = "Panel Workshop",
    )
    else -> null
}
