package com.example.optimalx.ui.dumpedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.SyneFamily

@Composable
fun PromoteDumpEditDialog(
    parentFolders: List<PromoteParentOption>,
    onDismiss: () -> Unit,
    onPromote: (parentFolderId: Long, subfolderName: String) -> Unit,
) {
    val colors = LocalOptimalXColors.current
    var subfolderName by remember { mutableStateOf("") }
    var selectedParentId by remember { mutableLongStateOf(parentFolders.firstOrNull()?.id ?: 0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = "Promote to folder",
                color = colors.textPrimary,
                fontFamily = SyneFamily,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    text = "Save the buffer as a note in a new subfolder. The buffer stays until you clear it.",
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                if (parentFolders.isEmpty()) {
                    Text(
                        text = "Create a parent folder first.",
                        color = colors.textDim,
                        fontFamily = DmSansFamily,
                        fontSize = 14.sp,
                    )
                } else {
                    Text(
                        text = "Parent folder",
                        color = colors.textDim,
                        fontFamily = DmSansFamily,
                        fontSize = 12.sp,
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp),
                    ) {
                        items(parentFolders, key = { it.id }) { parent ->
                            val selected = parent.id == selectedParentId
                            Text(
                                text = parent.name,
                                color = if (selected) colors.accent else colors.textPrimary,
                                fontFamily = DmSansFamily,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedParentId = parent.id }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = subfolderName,
                        onValueChange = { subfolderName = it },
                        label = { Text("Subfolder name", fontFamily = DmSansFamily) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedParentId > 0L) {
                        onPromote(selectedParentId, subfolderName)
                    }
                },
                enabled = parentFolders.isNotEmpty() && subfolderName.trim().isNotBlank(),
            ) {
                Text("Promote", color = colors.accent, fontFamily = DmSansFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid, fontFamily = DmSansFamily)
            }
        },
    )
}
