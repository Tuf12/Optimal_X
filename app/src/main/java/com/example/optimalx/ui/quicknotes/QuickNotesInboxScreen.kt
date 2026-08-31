package com.example.optimalx.ui.quicknotes

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.quicknotes.QuickNoteEntry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.optimalx.ui.eidos.EidosChatViewModel
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QuickNotesInboxScreen(
    subfolderId: Long,
    eidosViewModel: EidosChatViewModel,
    onBack: () -> Unit,
    onEidosClick: () -> Unit,
    onEidosSectionClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
) {
    val viewModel: QuickNotesViewModel = viewModel(
        key = "quick_notes_$subfolderId",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                QuickNotesViewModel(app as Application, subfolderId)
            }
        },
    )

    val colors = LocalOptimalXColors.current
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val subfolderName by viewModel.subfolder.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val editingIndex by viewModel.editingIndex.collectAsState()

    var actionEntryIndex by remember { mutableIntStateOf(-1) }
    var pendingDeleteIndex by remember { mutableIntStateOf(-1) }

    BackHandler { onBack() }

    LaunchedEffect(subfolderId) {
        eidosViewModel.setQuickNotesInboxScope(subfolderId)
    }
    DisposableEffect(lifecycleOwner, subfolderId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                eidosViewModel.resyncQuickNotesInboxFromDatabase(subfolderId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            eidosViewModel.clearQuickNotesChatToolbarRestriction()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textMid,
                    actionIconContentColor = colors.textMid,
                ),
                title = {
                    Column {
                        Text(
                            text = "Quick Notes",
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = subfolderName?.name.orEmpty(),
                            fontFamily = DmMonoFamily,
                            color = colors.textDim,
                            fontSize = 13.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textMid,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onEidosClick) {
                        Text("Eidos", color = colors.accent, fontFamily = DmSansFamily)
                    }
                    if (onEidosSectionClick != null) {
                        TextButton(onClick = onEidosSectionClick) {
                            Text("Section", color = colors.textMid, fontFamily = DmSansFamily)
                        }
                    }
                    if (onSettingsClick != null) {
                        TextButton(onClick = onSettingsClick) {
                            Text("⋯", color = colors.textMid, fontSize = 18.sp)
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (editingIndex != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Editing entry",
                            color = colors.accent,
                            fontFamily = DmSansFamily,
                            fontSize = 13.sp,
                        )
                        TextButton(onClick = { viewModel.cancelEdit() }) {
                            Text("Cancel edit", color = colors.textMid)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = viewModel::setDraft,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (editingIndex != null) "Edit entry…" else "New entry…",
                            color = colors.textDim,
                            fontFamily = DmSansFamily,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = colors.textPrimary,
                        fontFamily = DmSansFamily,
                    ),
                    shape = RoundedCornerShape(14.dp),
                )
                IconButton(
                    onClick = { viewModel.sendDraft() },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (draft.isNotBlank()) colors.accent else colors.textDim,
                    )
                }
                }
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No entries yet.\nType below and tap send.",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 15.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(entries, key = { idx, _ -> idx }) { index, entry ->
                    QuickNoteEntryRow(
                        entry = entry,
                        onLongClick = { actionEntryIndex = index },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (actionEntryIndex >= 0 && actionEntryIndex < entries.size) {
        val entry = entries[actionEntryIndex]
        AlertDialog(
            onDismissRequest = { actionEntryIndex = -1 },
            title = { Text("Entry", fontFamily = DmSansFamily) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "[${entry.timeLabel}] ${entry.body}",
                        fontFamily = DmSansFamily,
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                    )
                    TextButton(
                        onClick = {
                            viewModel.beginEdit(actionEntryIndex)
                            actionEntryIndex = -1
                        },
                    ) { Text("Edit", color = colors.accent) }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString("[${entry.timeLabel}] ${entry.body}"))
                            actionEntryIndex = -1
                        },
                    ) { Text("Copy") }
                    TextButton(
                        onClick = {
                            pendingDeleteIndex = actionEntryIndex
                            actionEntryIndex = -1
                        },
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionEntryIndex = -1 }) {
                    Text("Close")
                }
            },
        )
    }

    if (pendingDeleteIndex >= 0) {
        AlertDialog(
            onDismissRequest = { pendingDeleteIndex = -1 },
            title = { Text("Delete entry?", fontFamily = DmSansFamily) },
            text = { Text("This cannot be undone.", fontFamily = DmSansFamily) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteEntry(pendingDeleteIndex)
                        pendingDeleteIndex = -1
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteIndex = -1 }) { Text("Cancel") }
            },
        )
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickNoteEntryRow(
    entry: QuickNoteEntry,
    onLongClick: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "[${entry.timeLabel}]",
                fontFamily = DmMonoFamily,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.body,
                fontFamily = DmSansFamily,
                color = colors.textPrimary,
                fontSize = 15.sp,
            )
        }
    }
}
