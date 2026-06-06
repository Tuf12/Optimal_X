package com.example.optimalx.ui.folders.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun FolderBottomBar(
    isGrid: Boolean,
    showTrashButton: Boolean,
    showCreateButton: Boolean = true,
    onCreateClick: () -> Unit,
    onSortClick: () -> Unit,
    onGridToggle: () -> Unit,
    onTrashClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .border(width = 1.dp, color = colors.border, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCreateButton) {
            IconButton(onClick = onCreateClick) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create folder",
                    tint = colors.accent,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        IconButton(onClick = onSortClick) {
            Icon(
                imageVector = Icons.Default.Sort,
                contentDescription = "Sort",
                tint = colors.textMid,
                modifier = Modifier.size(22.dp),
            )
        }

        IconButton(onClick = onGridToggle) {
            Icon(
                imageVector = if (isGrid) Icons.Default.List else Icons.Default.GridView,
                contentDescription = "Switch layout mode",
                tint = colors.textMid,
                modifier = Modifier.size(22.dp),
            )
        }

        if (showTrashButton) {
            IconButton(onClick = onTrashClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Trash",
                    tint = colors.textMid,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
