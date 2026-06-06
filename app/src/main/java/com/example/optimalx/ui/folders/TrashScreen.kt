package com.example.optimalx.ui.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.optimalx.data.model.ParentFolder
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.data.repository.FolderRepository
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.SyneFamily

@Composable
fun TrashScreen(
    repository: FolderRepository,
    onBack: () -> Unit,
    onNavigateToFolder: (Long) -> Unit,
) {
    BackHandler { onBack() }

    val viewModel: TrashViewModel = viewModel()
    val deletedParentFolders by viewModel.deletedParentFolders.collectAsState()
    val deletedSubfolders by viewModel.deletedSubfolders.collectAsState()
    val colors = LocalOptimalXColors.current
    val isEmpty = deletedParentFolders.isEmpty() && deletedSubfolders.isEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textMid,
                )
            }
            Text(
                text = "Trash",
                color = colors.textPrimary,
                fontFamily = SyneFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        if (isEmpty) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Trash is empty", color = colors.textDim, fontFamily = DmSansFamily, fontSize = 15.sp)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .navigationBarsPadding(),
            ) {
                if (deletedParentFolders.isNotEmpty()) {
                    item {
                        SectionLabel("Folders")
                    }
                    items(deletedParentFolders, key = { "pf_${it.id}" }) { folder ->
                        TrashItemRow(
                            name = folder.name,
                            subtitle = "Folder",
                            onRestore = { viewModel.restoreParentFolder(folder.id) },
                            onDelete = { viewModel.permanentlyDeleteParentFolder(folder.id) },
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }

                if (deletedSubfolders.isNotEmpty()) {
                    item { SectionLabel("Subfolders") }
                    items(deletedSubfolders, key = { "sf_${it.id}" }) { subfolder ->
                        TrashItemRow(
                            name = subfolder.name,
                            subtitle = "Subfolder",
                            onRestore = { viewModel.restoreSubfolder(subfolder.id) },
                            onDelete = { viewModel.permanentlyDeleteSubfolder(subfolder.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalOptimalXColors.current
    Text(
        text = text.uppercase(),
        color = colors.textDim,
        fontFamily = DmMonoFamily,
        fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun TrashItemRow(
    name: String,
    subtitle: String,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Text(
                text = subtitle,
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
        }
        Row {
            TextButton(onClick = onRestore) {
                Text("Restore", color = colors.accent, fontFamily = DmSansFamily, fontSize = 13.sp)
            }
            TextButton(onClick = onDelete) {
                Text("Delete", color = colors.badgePdfText, fontFamily = DmSansFamily, fontSize = 13.sp)
            }
        }
    }
}
