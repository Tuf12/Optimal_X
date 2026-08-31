package com.example.optimalx.ui.eidos

import android.app.Application
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.eidos.EidosSystemMemoryFormat
import com.example.optimalx.data.eidos.EidosSystemMemoryRepository
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.ui.folders.components.FolderCard
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class EidosSystemKind(
    val routeValue: String,
    val title: String,
    val systemFolderName: String,
    val readOnlyNote: Boolean,
) {
    JOURNAL("journal", "Eidos Journal", SystemFolderNames.EIDOS_JOURNAL, true),
    LOG("log", "Eidos Log", SystemFolderNames.EIDOS_LOG, true),
    DAILY("daily", "Eidos Daily", SystemFolderNames.EIDOS_DAILY, true),
    MEMORY("memory", "Eidos Memory", SystemFolderNames.EIDOS_MEMORY, true),
    CHATS("chats", "Chats", SystemFolderNames.EIDOS_CHATS, false),
    ;

    companion object {
        fun fromRoute(value: String?): EidosSystemKind =
            entries.firstOrNull { it.routeValue == value } ?: JOURNAL
    }
}

data class ParsedLogLine(
    val fullText: String,
    val location: String?,
    val anchor: String?,
)

class EidosSystemFolderViewModel(
    app: Application,
    private val kind: EidosSystemKind,
) : ViewModel() {
    private val appRef = app as OptimalXApplication
    private val db = appRef.database

    private val _entries = MutableStateFlow<List<Subfolder>>(emptyList())
    val entries: StateFlow<List<Subfolder>> = _entries.asStateFlow()

    init {
        viewModelScope.launch {
            val parent = db.parentFolderDao().getSystemFolderByName(kind.systemFolderName) ?: return@launch
            db.subfolderDao().getAllActiveByParent(parent.id).collect { subfolders ->
                _entries.value = subfolders
                    .filter { it.deletedAt == null }
                    .sortedByDescending { it.updatedAt }
            }
        }
    }

    fun deleteSelected(selectedIds: Set<Long>) {
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            db.withTransaction {
                selectedIds.forEach { db.subfolderDao().deleteById(it) }
            }
        }
    }
}

class EidosSystemFolderViewModelFactory(
    private val app: Application,
    private val kind: EidosSystemKind,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return EidosSystemFolderViewModel(app, kind) as T
    }
}

private val updatedAtFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

class EidosSystemMemoryInboxViewModel(
    app: Application,
    private val kind: EidosSystemKind,
) : ViewModel() {
    private val appRef = app as OptimalXApplication
    private val repository = EidosSystemMemoryRepository(
        db = appRef.database,
        semanticIndexer = appRef.semanticIndexer,
        semanticChunkBuilder = appRef.semanticChunkBuilder,
    )

    private val _entries = MutableStateFlow<List<EidosSystemMemoryFormat.Entry>>(emptyList())
    val entries: StateFlow<List<EidosSystemMemoryFormat.Entry>> = _entries.asStateFlow()

    init {
        viewModelScope.launch {
            val parent = appRef.database.parentFolderDao().getSystemFolderByName(kind.systemFolderName)
                ?: return@launch
            appRef.database.subfolderDao().getAllActiveByParent(parent.id).collect { subfolders ->
                val parsed = subfolders
                    .filter { it.deletedAt == null }
                    .flatMap { sf ->
                        val note = appRef.database.noteDao().getBySubfolderOnce(sf.id)
                        EidosSystemMemoryFormat.parseEntries(
                            subfolderId = sf.id,
                            subfolderName = sf.name,
                            noteContent = note?.content.orEmpty(),
                            fallbackUpdatedAt = sf.updatedAt,
                        )
                    }
                    .sortedByDescending { it.sortKey }
                _entries.value = parsed
            }
        }
    }

    fun deleteEntry(entry: EidosSystemMemoryFormat.Entry) {
        viewModelScope.launch {
            repository.deleteEntry(entry.subfolderId, entry.chunkIndex)
        }
    }
}

class EidosSystemMemoryInboxViewModelFactory(
    private val app: Application,
    private val kind: EidosSystemKind,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return EidosSystemMemoryInboxViewModel(app, kind) as T
    }
}

@Composable
fun EidosSectionScreen(
    onBack: () -> Unit,
    onOpen: (EidosSystemKind) -> Unit,
    apiTraceEnabled: Boolean = false,
    onOpenApiTrace: () -> Unit = {},
) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("Back", color = colors.textMid, fontFamily = DmSansFamily)
            }
            Text(
                text = "Eidos",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
            )
        }

        val sectionKinds = EidosSystemKind.entries.filterNot {
            it == EidosSystemKind.CHATS
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (apiTraceEnabled) {
                item(key = "api_trace") {
                    FolderCard(
                        name = "API Trace",
                        isGrid = false,
                        onClick = onOpenApiTrace,
                        onLongClick = {},
                    )
                }
            }
            items(sectionKinds) { kind ->
                FolderCard(
                    name = kind.title,
                    isGrid = false,
                    onClick = { onOpen(kind) },
                    onLongClick = {},
                )
            }
        }
    }
}

@Composable
fun EidosSystemFolderScreen(
    kind: EidosSystemKind,
    onBack: () -> Unit,
    onOpenNote: (subfolderId: Long) -> Unit,
) {
    if (kind == EidosSystemKind.DAILY || kind == EidosSystemKind.MEMORY) {
        EidosSystemInboxListScreen(kind = kind, onBack = onBack)
        return
    }

    val colors = LocalOptimalXColors.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
    val vm: EidosSystemFolderViewModel = viewModel(
        key = "eidos_folder_${kind.routeValue}",
        factory = EidosSystemFolderViewModelFactory(app, kind),
    )
    val entries by vm.entries.collectAsState()
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("Back", color = colors.textMid, fontFamily = DmSansFamily)
            }
            Text(
                text = kind.title,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    vm.deleteSelected(selectedIds)
                    selectedIds = emptySet()
                },
                enabled = selectedIds.isNotEmpty(),
            ) {
                Text(
                    text = "Delete selected",
                    color = if (selectedIds.isNotEmpty()) colors.accent else colors.textDim,
                    fontFamily = DmSansFamily,
                )
            }
        }

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No entries yet",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 15.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    val isSelected = selectedIds.contains(entry.id)
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth()
                            .height(70.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                            .clickable { onOpenNote(entry.id) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + entry.id else selectedIds - entry.id
                            },
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp, end = 8.dp),
                        ) {
                            Text(
                                text = entry.name,
                                color = colors.textPrimary,
                                fontFamily = DmSansFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = updatedAtFormatter.format(Date(entry.updatedAt)),
                                color = colors.textDim,
                                fontFamily = DmMonoFamily,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EidosSystemInboxListScreen(
    kind: EidosSystemKind,
    onBack: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
    val vm: EidosSystemMemoryInboxViewModel = viewModel(
        key = "eidos_memory_inbox_${kind.routeValue}",
        factory = EidosSystemMemoryInboxViewModelFactory(app, kind),
    )
    val entries by vm.entries.collectAsState()
    var pendingDelete by remember { mutableStateOf<EidosSystemMemoryFormat.Entry?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("Back", color = colors.textMid, fontFamily = DmSansFamily)
            }
            Text(
                text = kind.title,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = "Long-press an entry to delete",
            color = colors.textDim,
            fontFamily = DmSansFamily,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No entries yet",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 15.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    entries,
                    key = { "${it.subfolderId}_${it.chunkIndex}_${it.rawChunk.hashCode()}" },
                ) { entry ->
                    EidosSystemMemoryEntryRow(
                        entry = entry,
                        onLongClick = { pendingDelete = entry },
                    )
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete memory entry?", fontFamily = DmSansFamily) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val heading = listOfNotNull(entry.subfolderName, entry.timestampLabel).joinToString(" • ")
                    if (heading.isNotBlank()) {
                        Text(
                            text = heading,
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                        )
                    }
                    Text(
                        text = entry.content,
                        color = colors.textPrimary,
                        fontFamily = DmSansFamily,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "This cannot be undone.",
                        color = colors.textMid,
                        fontFamily = DmSansFamily,
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteEntry(entry)
                        pendingDelete = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", fontFamily = DmSansFamily)
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EidosSystemMemoryEntryRow(
    entry: EidosSystemMemoryFormat.Entry,
    onLongClick: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.borderSoft, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
            )
            .padding(10.dp),
    ) {
        val heading = listOfNotNull(entry.subfolderName, entry.timestampLabel).joinToString(" • ")
        if (heading.isNotBlank()) {
            Text(
                text = heading,
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
        }
        Text(
            text = entry.content,
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun EidosSystemNoteScreen(
    kind: EidosSystemKind,
    subfolderId: Long,
    onBack: () -> Unit,
    onOpenDeepLink: (location: String, anchor: String?) -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as OptimalXApplication
    val note by app.database.noteDao().getBySubfolder(subfolderId).collectAsState(initial = null)
    var subfolderName by remember(subfolderId) { mutableStateOf("Entry") }

    LaunchedEffect(subfolderId) {
        subfolderName = app.database.subfolderDao().getById(subfolderId)?.name ?: "Entry"
    }

    val content = note?.content.orEmpty()
    val parsedLogLines = remember(content) {
        content.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val location = Regex("""(?:\||\s)location=([^|]+)""").find(line)?.groupValues?.getOrNull(1)?.trim()
                val anchor = Regex("""(?:\||\s)anchor=([^|]+)""").find(line)?.groupValues?.getOrNull(1)?.trim()
                ParsedLogLine(fullText = line.trim(), location = location, anchor = anchor)
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("Back", color = colors.textMid, fontFamily = DmSansFamily)
            }
            Text(
                text = subfolderName,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (kind == EidosSystemKind.LOG) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(parsedLogLines) { row ->
                    val canOpen = !row.location.isNullOrBlank()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.borderSoft, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { ctx ->
                                TextView(ctx).apply {
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                                    setTextIsSelectable(true)
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    setLineSpacing(0f, 20f / 14f)
                                }
                            },
                            update = { tv ->
                                tv.text = row.fullText
                                tv.setTextColor(colors.textPrimary.toArgb())
                            },
                        )
                        if (canOpen) {
                            Text(
                                text = "Open referenced note",
                                color = colors.accent,
                                fontFamily = DmMonoFamily,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clickable {
                                        onOpenDeepLink(row.location!!, row.anchor)
                                    },
                            )
                        }
                    }
                }
            }
        } else {
            val scrollState = androidx.compose.foundation.rememberScrollState()
            // Native TextView: reliable selection/copy for read-only system notes.
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .navigationBarsPadding()
                    .padding(16.dp),
                factory = { ctx ->
                    TextView(ctx).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setTextIsSelectable(true)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setLineSpacing(0f, 24f / 15f)
                    }
                },
                update = { tv ->
                    tv.text = content.ifBlank { "No content yet" }
                    tv.setTextColor(colors.textPrimary.toArgb())
                },
            )
        }
    }
}

@Composable
fun LinkedNoteScreen(
    subfolderId: Long,
    anchor: String?,
    onBack: () -> Unit,
    onOpenInEditor: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as OptimalXApplication
    val note by app.database.noteDao().getBySubfolder(subfolderId).collectAsState(initial = null)
    var title by remember(subfolderId) { mutableStateOf("Linked Note") }

    LaunchedEffect(subfolderId) {
        val subfolder = app.database.subfolderDao().getById(subfolderId)
        val parent = subfolder?.let { app.database.parentFolderDao().getById(it.parentFolderId) }
        title = if (subfolder != null && parent != null) "${parent.name} / ${subfolder.name}" else "Linked Note"
    }

    val lines = remember(note?.content) { note?.content?.lines().orEmpty() }
    val displayText = remember(lines, anchor, colors.accentDim) {
        buildLinkedNoteDisplayText(lines, anchor, colors.accentDim.toArgb())
    }
    val scrollState = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(lines, anchor) {
        val marker = anchor?.trim()
        if (!marker.isNullOrBlank()) {
            val idx = lines.indexOfFirst { it.contains(marker, ignoreCase = true) }
            if (idx >= 0) {
                val lineHeightPx = with(density) { 20.sp.toPx() }
                scrollState.scrollTo((idx * lineHeightPx).toInt())
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("Back", color = colors.textMid, fontFamily = DmSansFamily)
            }
            Text(
                text = title,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(displayText.toString())) },
                enabled = displayText.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy note",
                    tint = colors.textMid,
                )
            }
            TextButton(onClick = onOpenInEditor) {
                Text("Open note", color = colors.accent, fontFamily = DmSansFamily)
            }
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            factory = { ctx ->
                TextView(ctx).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextIsSelectable(true)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setLineSpacing(0f, 20f / 14f)
                }
            },
            update = { tv ->
                tv.text = displayText
                tv.setTextColor(colors.textPrimary.toArgb())
            },
        )
    }
}

private fun buildLinkedNoteDisplayText(
    lines: List<String>,
    anchor: String?,
    highlightColorArgb: Int,
): CharSequence {
    if (lines.isEmpty()) return "No note content"
    val full = lines.joinToString("\n")
    val marker = anchor?.trim()
    if (marker.isNullOrBlank()) return full
    val spannable = SpannableString(full)
    var searchFrom = 0
    lines.forEach { line ->
        if (!line.contains(marker, ignoreCase = true)) return@forEach
        val lineStart = full.indexOf(line, searchFrom)
        if (lineStart < 0) return@forEach
        spannable.setSpan(
            BackgroundColorSpan(highlightColorArgb),
            lineStart,
            lineStart + line.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        searchFrom = lineStart + line.length
    }
    return spannable
}

fun parseSubfolderIdFromLocation(location: String): Long? {
    val normalized = location.trim()
    val colon = Regex("""subfolder:(\d+)""").find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()
    if (colon != null) return colon
    return Regex("""subfolderId=(\d+)""").find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()
}
