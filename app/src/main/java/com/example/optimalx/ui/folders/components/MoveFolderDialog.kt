package com.example.optimalx.ui.folders.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun MoveFolderDialog(
    currentParentId: Long,
    parentFolders: List<ParentFolder>,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    var selectedId by remember { mutableLongStateOf(currentParentId) }
    val available = parentFolders.filter { it.id != currentParentId }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(16.dp))
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(
                "Move to",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            if (available.isEmpty()) {
                Text(
                    "No other folders to move to.",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(available) { folder ->
                        val isSelected = folder.id == selectedId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedId = folder.id }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = folder.name,
                                color = if (isSelected) colors.accent else colors.textPrimary,
                                fontFamily = DmSansFamily,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Spacer(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(colors.accent),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = colors.textMid, fontFamily = DmSansFamily)
                }
                if (available.isNotEmpty()) {
                    TextButton(
                        onClick = { onConfirm(selectedId) },
                        enabled = selectedId != currentParentId,
                    ) {
                        Text("Move", color = colors.accent, fontFamily = DmSansFamily, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
