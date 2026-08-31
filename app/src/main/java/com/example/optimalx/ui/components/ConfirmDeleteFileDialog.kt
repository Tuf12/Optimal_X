package com.example.optimalx.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun ConfirmDeleteFileDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalOptimalXColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = "Move to trash?",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                text = "Move \"$fileName\" to trash? You can restore it from Trash.",
                color = colors.textMid,
                fontFamily = DmSansFamily,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Move to trash", color = colors.badgePdfText, fontFamily = DmSansFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid, fontFamily = DmSansFamily)
            }
        },
    )
}
