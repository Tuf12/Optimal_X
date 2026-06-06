package com.example.optimalx.ui.editor.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

/** DumpEdit menu — note editor items minus summary, plus Clear / Undo clear. */
@Composable
fun DumpEditDropdownMenu(
    expanded: Boolean,
    isViewMode: Boolean,
    isAiLocked: Boolean,
    isAiBlind: Boolean,
    undoClearAvailable: Boolean,
    onDismiss: () -> Unit,
    onToggleStrikethrough: () -> Unit,
    onToggleViewMode: () -> Unit,
    onToggleAiLock: () -> Unit,
    onToggleAiBlind: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onUndoClear: () -> Unit,
    onPromote: () -> Unit,
) {
    val colors = LocalOptimalXColors.current

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = colors.surface2,
    ) {
        DropdownMenuItem(
            text = { MenuItem("Strikethrough") },
            onClick = { onDismiss(); onToggleStrikethrough() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = { MenuItem(if (isViewMode) "Switch to Edit mode" else "Switch to View mode") },
            onClick = { onDismiss(); onToggleViewMode() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = {
                MenuItem(
                    text = if (isAiLocked) "Unlock for Eidos" else "Lock from Eidos",
                    color = if (isAiLocked) colors.accent else colors.textPrimary,
                )
            },
            onClick = { onDismiss(); onToggleAiLock() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = {
                MenuItem(
                    text = if (isAiBlind) "Reveal to Eidos" else "Blind from Eidos",
                    color = if (isAiBlind) colors.accent else colors.textPrimary,
                )
            },
            onClick = { onDismiss(); onToggleAiBlind() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = { MenuItem("Export buffer") },
            onClick = { onDismiss(); onExport() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = { MenuItem("Share buffer") },
            onClick = { onDismiss(); onShare() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = { MenuItem("Promote to folder") },
            onClick = { onDismiss(); onPromote() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = { MenuItem("Clear buffer", color = colors.accent) },
            onClick = { onDismiss(); onClear() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        if (undoClearAvailable) {
            DropdownMenuItem(
                text = { MenuItem("Undo clear") },
                onClick = { onDismiss(); onUndoClear() },
                colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
            )
        }
    }
}

@Composable
private fun MenuItem(
    text: String,
    color: androidx.compose.ui.graphics.Color = LocalOptimalXColors.current.textPrimary,
) {
    Text(text = text, color = color, fontFamily = DmSansFamily, fontSize = 15.sp)
}
