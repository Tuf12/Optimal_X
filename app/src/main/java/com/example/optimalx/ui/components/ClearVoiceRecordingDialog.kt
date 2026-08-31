package com.example.optimalx.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun ClearVoiceRecordingDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Clear recording?",
                fontFamily = DmSansFamily,
                color = colors.textPrimary,
            )
        },
        text = {
            Text(
                "This will discard the captured audio. Nothing will be transcribed or sent.",
                fontFamily = DmSansFamily,
                color = colors.textMid,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Clear", color = colors.accent, fontFamily = DmSansFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep", color = colors.textMid, fontFamily = DmSansFamily)
            }
        },
        containerColor = colors.sheetBackground,
    )
}
