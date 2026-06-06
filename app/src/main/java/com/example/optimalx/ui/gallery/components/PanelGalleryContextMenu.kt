package com.example.optimalx.ui.gallery.components

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
fun PanelGalleryContextMenu(
    expanded: Boolean,
    isPinnedToHome: Boolean,
    onDismiss: () -> Unit,
    onPinToHome: () -> Unit,
    onUnpinFromHome: () -> Unit,
) {
    val colors = LocalOptimalXColors.current

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = colors.surface2,
    ) {
        if (isPinnedToHome) {
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
        } else {
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
}
