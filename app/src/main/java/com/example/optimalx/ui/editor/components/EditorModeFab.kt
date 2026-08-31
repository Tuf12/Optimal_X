package com.example.optimalx.ui.editor.components

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

/** Prominent mode switch — Edit FAB when viewing, optional View chip when editing. */
@Composable
fun EditorModeFab(
    isViewMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val contentColor = Color(0xFF0E0E0F)

    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .navigationBarsPadding()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = colors.accent,
        contentColor = contentColor,
    ) {
        Icon(
            imageVector = if (isViewMode) Icons.Default.Edit else Icons.Default.Visibility,
            contentDescription = null,
        )
        Text(
            text = if (isViewMode) "Edit" else "View",
            fontFamily = DmSansFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
    }
}
