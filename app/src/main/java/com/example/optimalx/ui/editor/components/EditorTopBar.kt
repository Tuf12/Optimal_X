package com.example.optimalx.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.SyneFamily

@Composable
fun EditorTopBar(
    subfolderName: String,
    onTitleClick: (() -> Unit)? = null,
    onEidosClick: () -> Unit,
    onEidosSectionClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    onAddPanelClick: (() -> Unit)? = null,
    onHistoryClick: (() -> Unit)? = null,
    showCommit: Boolean = false,
    onCommitClick: (() -> Unit)? = null,
    pendingChangeCount: Int = 0,
    onReviewClick: (() -> Unit)? = null,
    saveStatusLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = subfolderName,
            color = colors.textPrimary,
            fontFamily = SyneFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .let { base ->
                    if (onTitleClick != null) base.clickable(onClick = onTitleClick) else base
                },
        )
        if (saveStatusLabel != null) {
            Text(
                text = saveStatusLabel,
                color = colors.textDim,
                fontFamily = DmSansFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (showCommit && onCommitClick != null) {
            TextButton(onClick = onCommitClick) {
                Text(
                    text = "Commit",
                    color = colors.accent,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
            }
        }
        if (pendingChangeCount > 0 && onReviewClick != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accentDim)
                    .border(1.dp, colors.accentBorder, RoundedCornerShape(6.dp))
                    .clickable(onClick = onReviewClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Review $pendingChangeCount",
                    color = colors.accent,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                )
            }
        }
        TextButton(onClick = onEidosClick) {
            Text(
                text = "Eidos",
                color = colors.accent,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
        }
        if (onEidosSectionClick != null) {
            TextButton(onClick = onEidosSectionClick) {
                Text(
                    text = "Section",
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
            }
        }
        if (onHistoryClick != null) {
            IconButton(onClick = onHistoryClick) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "History",
                    tint = colors.textMid,
                )
            }
        }
        if (onAddPanelClick != null) {
            IconButton(onClick = onAddPanelClick) {
                Icon(
                    imageVector = Icons.Default.Dashboard,
                    contentDescription = "Add panel",
                    tint = colors.textMid,
                )
            }
        }
        if (onSettingsClick != null) {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Settings",
                    tint = colors.textMid,
                )
            }
        }
    }
}
