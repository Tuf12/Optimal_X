package com.example.optimalx.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.ui.folders.components.CreateFolderDialog
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.OptimalXTheme

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }

        setContent {
            OptimalXTheme {
                val vm: ShareImportViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!! as Application
                            ShareImportViewModel(app)
                        }
                    }
                )

                val importState by vm.importState.collectAsState()

                // Close activity when import is done
                LaunchedEffect(importState) {
                    if (importState is ImportState.Done) {
                        finish()
                    }
                }

                ShareScreen(
                    uris = uris,
                    vm = vm,
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun extractUris(intent: Intent): List<Uri> {
        return when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        ?: emptyList()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
                }
            }
            else -> {
                // ACTION_SEND — single file in EXTRA_STREAM
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }
        }
    }
}

private sealed class ImportFolderCreateDialog {
    data object None : ImportFolderCreateDialog()
    data object NewParent : ImportFolderCreateDialog()
    data class NewSubfolder(val parentId: Long, val parentName: String) : ImportFolderCreateDialog()
}

@Composable
private fun ShareScreen(
    uris: List<Uri>,
    vm: ShareImportViewModel,
    onDismiss: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val context = LocalContext.current

    val folders by vm.folders.collectAsState()
    val foldersLoaded by vm.foldersLoaded.collectAsState()
    val selectedSubfolderId by vm.selectedSubfolderId.collectAsState()
    val importState by vm.importState.collectAsState()
    var createFolderDialog by remember { mutableStateOf<ImportFolderCreateDialog>(ImportFolderCreateDialog.None) }

    val busyImporting = importState is ImportState.Importing

    // Derive label from observed state (never call vm.selectedLabel() from composition)
    val selectedLabel: String? = selectedSubfolderId?.let { subId ->
        folders.firstNotNullOfOrNull { parent ->
            parent.subfolders.find { it.id == subId }?.let { "${parent.folder.name} / ${it.name}" }
        }
    }

    when (val dialog = createFolderDialog) {
        is ImportFolderCreateDialog.NewParent -> CreateFolderDialog(
            label = "New parent folder",
            onConfirm = { name ->
                vm.createParentFolder(name)
                createFolderDialog = ImportFolderCreateDialog.None
            },
            onDismiss = { createFolderDialog = ImportFolderCreateDialog.None },
        )
        is ImportFolderCreateDialog.NewSubfolder -> CreateFolderDialog(
            label = "New subfolder in ${dialog.parentName}",
            onConfirm = { name ->
                vm.createSubfolder(dialog.parentId, name)
                createFolderDialog = ImportFolderCreateDialog.None
            },
            onDismiss = { createFolderDialog = ImportFolderCreateDialog.None },
        )
        ImportFolderCreateDialog.None -> Unit
    }

    // Full-screen container with scrim
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(enabled = importState !is ImportState.Importing, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Sheet
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.sheetBackground, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .border(
                    1.dp,
                    colors.sheetBorder,
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                )
                .clickable(enabled = false) {} // eat clicks so scrim tap-to-dismiss still works
                .navigationBarsPadding()
                .statusBarsPadding(),
        ) {
            // ── Header ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Save to OptimalX",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    enabled = importState !is ImportState.Importing,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = colors.textDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            HorizontalDivider(color = colors.border, thickness = 1.dp)

            // ── File names ────────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = if (uris.size == 1) "1 file" else "${uris.size} files",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(2.dp))
                uris.take(3).forEach { uri ->
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
                    Text(
                        text = name,
                        color = colors.textMid,
                        fontFamily = DmMonoFamily,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (uris.size > 3) {
                    Text(
                        text = "…and ${uris.size - 3} more",
                        color = colors.textDim,
                        fontFamily = DmSansFamily,
                        fontSize = 12.sp,
                    )
                }
            }

            HorizontalDivider(color = colors.border, thickness = 1.dp)

            // ── Destination header ────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Select destination",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { createFolderDialog = ImportFolderCreateDialog.NewParent },
                    enabled = !busyImporting && foldersLoaded,
                ) {
                    Text(
                        text = "New folder",
                        color = colors.accent,
                        fontFamily = DmSansFamily,
                        fontSize = 13.sp,
                    )
                }
            }

            // ── Folder picker ─────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                if (!foldersLoaded && folders.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = colors.accent,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                if (foldersLoaded && folders.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 20.dp),
                        ) {
                            Text(
                                text = "You do not have a workspace folder yet. Create one to save files here.",
                                color = colors.textMid,
                                fontFamily = DmSansFamily,
                                fontSize = 14.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(
                                onClick = { createFolderDialog = ImportFolderCreateDialog.NewParent },
                                enabled = !busyImporting,
                            ) {
                                Text(
                                    text = "Create parent folder",
                                    color = colors.accent,
                                    fontFamily = DmSansFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }

                items(folders, key = { it.folder.id }) { pickerParent ->
                    // Parent folder row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.toggleExpand(pickerParent.folder.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (pickerParent.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = colors.textDim,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = pickerParent.folder.name,
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Subfolders (shown when expanded)
                    if (pickerParent.isExpanded) {
                        if (pickerParent.subfolders.isEmpty()) {
                            Column(
                                modifier = Modifier.padding(start = 42.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                            ) {
                                Text(
                                    text = "No subfolders yet",
                                    color = colors.textDim,
                                    fontFamily = DmSansFamily,
                                    fontSize = 13.sp,
                                )
                                TextButton(
                                    onClick = {
                                        createFolderDialog = ImportFolderCreateDialog.NewSubfolder(
                                            pickerParent.folder.id,
                                            pickerParent.folder.name,
                                        )
                                    },
                                    enabled = !busyImporting,
                                    modifier = Modifier.padding(start = (-8).dp),
                                ) {
                                    Text(
                                        text = "Create subfolder",
                                        color = colors.accent,
                                        fontFamily = DmSansFamily,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        } else {
                            pickerParent.subfolders.forEach { subfolder ->
                                SubfolderRow(
                                    subfolder = subfolder,
                                    isSelected = selectedSubfolderId == subfolder.id,
                                    onClick = { vm.selectSubfolder(subfolder.id) },
                                )
                            }
                            TextButton(
                                onClick = {
                                    createFolderDialog = ImportFolderCreateDialog.NewSubfolder(
                                        pickerParent.folder.id,
                                        pickerParent.folder.name,
                                    )
                                },
                                enabled = !busyImporting,
                                modifier = Modifier.padding(start = 34.dp, end = 16.dp, bottom = 4.dp),
                            ) {
                                Text(
                                    text = "New subfolder",
                                    color = colors.accent,
                                    fontFamily = DmSansFamily,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = colors.border,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // ── Save button ───────────────────────────────────────────────
            val canSave = selectedSubfolderId != null && importState !is ImportState.Importing

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                if (selectedLabel != null) {
                    Text(
                        text = selectedLabel,
                        color = colors.textDim,
                        fontFamily = DmSansFamily,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                Button(
                    onClick = { vm.importFiles(context, uris) },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        disabledContainerColor = colors.surface2,
                        contentColor = Color(0xFF0E0E0F),
                        disabledContentColor = colors.textDim,
                    ),
                ) {
                    if (importState is ImportState.Importing) {
                        CircularProgressIndicator(
                            color = Color(0xFF0E0E0F),
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = if (selectedSubfolderId == null) "Select a folder first" else "Save Here",
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                    }
                }

                if (importState is ImportState.Error) {
                    Text(
                        text = (importState as ImportState.Error).message,
                        color = Color(0xFFFF503C),
                        fontFamily = DmSansFamily,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SubfolderRow(
    subfolder: Subfolder,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalOptimalXColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isSelected) colors.accentDim else Color.Transparent,
            )
            .padding(start = 42.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = subfolder.name,
            color = if (isSelected) colors.accent else colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = colors.accent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
