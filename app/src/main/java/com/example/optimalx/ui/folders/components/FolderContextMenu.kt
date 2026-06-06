package com.example.optimalx.ui.folders.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun FolderContextMenu(
    expanded: Boolean,
    showMove: Boolean = false,
    showPinToHome: Boolean = false,
    isPinnedToHome: Boolean = false,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (() -> Unit)? = null,
    onPinToHome: (() -> Unit)? = null,
    onUnpinFromHome: (() -> Unit)? = null,
) {
    val colors = LocalOptimalXColors.current

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = colors.surface2,
    ) {
        if (showPinToHome) {
            if (isPinnedToHome && onUnpinFromHome != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Unpin from home",
                            color = colors.accent,
                            fontFamily = DmSansFamily,
                            fontSize = 15.sp,
                        )
                    },
                    onClick = { onDismiss(); onUnpinFromHome() },
                    colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
                )
            } else if (!isPinnedToHome && onPinToHome != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Pin to home",
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontSize = 15.sp,
                        )
                    },
                    onClick = { onDismiss(); onPinToHome() },
                    colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
                )
            }
        }
        DropdownMenuItem(
            text = {
                Text(
                    "Rename",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontSize = 15.sp,
                )
            },
            onClick = { onDismiss(); onRename() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        DropdownMenuItem(
            text = {
                Text(
                    "Delete",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontSize = 15.sp,
                )
            },
            onClick = { onDismiss(); onDelete() },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        if (showMove && onMove != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        "Move",
                        color = colors.textPrimary,
                        fontFamily = DmSansFamily,
                        fontSize = 15.sp,
                    )
                },
                onClick = { onDismiss(); onMove() },
                colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
            )
        }
    }
}
