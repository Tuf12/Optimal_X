package com.example.optimalx.ui.editor.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun EditorDropdownMenu(
    expanded: Boolean,
    isViewMode: Boolean,
    isAiLocked: Boolean,
    isAiBlind: Boolean,
    onDismiss: () -> Unit,
    onToggleViewMode: () -> Unit,
    onToggleAiLock: () -> Unit,
    onToggleAiBlind: () -> Unit,
    onExportPdf: () -> Unit,
    onExportMarkdown: () -> Unit,
    onSharePdf: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = LocalOptimalXColors.current

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = colors.surface2,
    ) {
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
            text = { MenuItem("Export as PDF") },
            onClick = { onDismiss(); onExportPdf() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = { MenuItem("Share as PDF") },
            onClick = { onDismiss(); onSharePdf() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = { MenuItem("Export as Markdown") },
            onClick = { onDismiss(); onExportMarkdown() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = { MenuItem("Share rendered note") },
            onClick = { onDismiss(); onShare() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = { MenuItem("Word / ODT export — desktop only", color = colors.textDim) },
            onClick = { onDismiss() },
            enabled = false,
            colors = MenuDefaults.itemColors(textColor = colors.textDim),
        )
    }
}

@Composable
private fun MenuItem(
    text: String,
    color: androidx.compose.ui.graphics.Color = LocalOptimalXColors.current.textPrimary,
) {
    Text(text = text, color = color, fontFamily = DmSansFamily, fontSize = 15.sp)
}
