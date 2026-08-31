package com.example.optimalx.ui.editor.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.data.model.FileReference
import com.example.optimalx.ui.components.ConfirmDeleteFileDialog
import com.example.optimalx.ui.editor.components.FileTypeBadge
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun FilesPanel(
    files: List<FileReference>,
    onFileClick: (FileReference) -> Unit,
    onImport: (android.net.Uri) -> Unit,
    onDelete: (FileReference) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val colors = LocalOptimalXColors.current
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<FileReference?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { onImport(it) } }

    val supportedMimeTypes = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.oasis.opendocument.text",
        "image/*",
        "text/plain",
        "text/markdown",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.presentation",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // Header row with import button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (loading) "Downloading from desktop…" else "Files",
                color = if (loading) colors.accent else colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
            )
            IconButton(
                onClick = { filePicker.launch(supportedMimeTypes) },
                enabled = !loading,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Import file",
                    tint = colors.accent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No files attached",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                items(files, key = { it.id }) { file ->
                    FileRow(
                        file = file,
                        onClick = { if (!loading) onFileClick(file) },
                        onDelete = { pendingDelete = file },
                        enabled = !loading,
                    )
                }
            }
        }
    }

    pendingDelete?.let { file ->
        ConfirmDeleteFileDialog(
            fileName = file.fileName,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                onDelete(file)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun FileRow(
    file: FileReference,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalOptimalXColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(colors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeBadge(fileType = file.fileType)
        Spacer(Modifier.width(12.dp))
        Text(
            text = file.fileName,
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete file",
                tint = colors.textDim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
