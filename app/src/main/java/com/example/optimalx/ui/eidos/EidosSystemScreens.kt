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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
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
import com.example.optimalx.data.eidos.EidosIndexFeature
import com.example.optimalx.data.db.SystemFolderNames
import com.example.optimalx.data.model.Subfolder
import com.example.optimalx.ui.folders.components.FolderCard
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    INDEX("index", "Eidos Index", SystemFolderNames.EIDOS_INDEX, true),
    REASONING("reasoning", "Reasoning", SystemFolderNames.EIDOS_REASONING, true),
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
    private val appIndexSync = appRef.appIndexSyncService

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
            appIndexSync.requestSync("eidos_system_delete_selected:${kind.routeValue}:${selectedIds.size}")
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
private val memoryLineTimestampRegex = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})]\s*(.*)$""")
private val memoryTimestampInputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val memoryTimestampOutputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private data class MemoryNoteEntry(
    val id: String,
    val subfolderName: String,
    val timestampLabel: String?,
    val content: String,
    val sortKey: Long,
)

private data class ReasoningLogItem(
    val id: String,
    val subfolderName: String,
    val timestamp: String?,
    val title: String,
    val preview: String,
    val fullContent: String,
    val sortKey: Long,
)

private data class ReasoningStructuredRecord(
    val type: String,
    val runId: String,
    val timestamp: String?,
    val obj: kotlinx.serialization.json.JsonObject,
)

private data class EidosIndexNode(
    val ref: String,
    val tag: String,
    val hint: String,
    val objectName: String,
    val parentFolderName: String? = null,
    val subfolderName: String? = null,
)

private data class EidosSubfolderNode(
    val branch: EidosIndexNode,
    val note: EidosIndexNode?,
    val files: List<EidosIndexNode>,
    val subfolderChats: List<EidosIndexNode>,
    val subfolderMemoryCache: EidosIndexNode?,
)

private data class EidosParentNode(
    val branch: EidosIndexNode,
    val subfolders: List<EidosSubfolderNode>,
    val parentChats: List<EidosIndexNode>,
)

private data class EidosIndexTree(
    val parents: List<EidosParentNode>,
    val generalChats: List<EidosIndexNode>,
    val journal: List<EidosIndexNode>,
    val quickNotes: List<EidosIndexNode>,
    val ltm: List<EidosIndexNode>,
)

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
            it == EidosSystemKind.CHATS ||
                it == EidosSystemKind.REASONING ||
                // ON HOLD — Eidos Index UI (see EidosIndexFeature); retrieval uses search_semantic.
                (!EidosIndexFeature.isActive && it == EidosSystemKind.INDEX)
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
    if (kind == EidosSystemKind.REASONING) {
        EidosReasoningLogDirectoryScreen(onBack = onBack)
        return
    }
    if (kind == EidosSystemKind.DAILY || kind == EidosSystemKind.MEMORY) {
        EidosSystemInboxListScreen(kind = kind, onBack = onBack)
        return
    }
    if (kind == EidosSystemKind.INDEX) {
        // ON HOLD — Eidos Index screen kept for revival; menu entry hidden via EidosIndexFeature.
        EidosIndexScreen(onBack = onBack)
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
private fun EidosIndexScreen(
    onBack: () -> Unit,
) {
    // ON HOLD — full index tree/JSON UI; see EidosIndexFeature and buildEidosIndexPayload below.
    if (!EidosIndexFeature.isActive) {
        val colors = LocalOptimalXColors.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .padding(16.dp),
        ) {
            TextButton(onClick = onBack) {
                Text("Back", color = colors.textMid, fontFamily = DmSansFamily)
            }
            Text(
                text = "Eidos Index is on hold. Use semantic search for retrieval.",
                color = colors.textMid,
                fontFamily = DmSansFamily,
                fontSize = 15.sp,
            )
        }
        return
    }
    val colors = LocalOptimalXColors.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as OptimalXApplication
    var indexPayload by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        app.database.parentFolderDao().getAllActive().collect {
            indexPayload = buildEidosIndexPayload(app)
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
                text = "Eidos Index",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
            )
        }

        val payload = indexPayload
        if (payload == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Loading index...",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 15.sp,
                )
            }
        } else {
            val scrollState = androidx.compose.foundation.rememberScrollState()
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                factory = { context ->
                    TextView(context).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextColor(colors.textPrimary.toArgb())
                        setTextIsSelectable(true)
                    }
                },
                update = { tv ->
                    tv.setTextColor(colors.textPrimary.toArgb())
                    tv.text = payload
                },
            )
        }
    }
}

@Composable
private fun EidosReasoningLogDirectoryScreen(
    onBack: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as OptimalXApplication
    var logs by remember { mutableStateOf<List<ReasoningLogItem>>(emptyList()) }
    var selectedLog by remember { mutableStateOf<ReasoningLogItem?>(null) }

    LaunchedEffect(Unit) {
        val parent = app.database.parentFolderDao().getSystemFolderByName(SystemFolderNames.EIDOS_REASONING)
        if (parent == null) {
            logs = emptyList()
            return@LaunchedEffect
        }
        app.database.subfolderDao().getAllActiveByParent(parent.id).collect { subfolders ->
            val merged = subfolders
                .filter { it.deletedAt == null }
                .flatMap { sf ->
                    val note = app.database.noteDao().getBySubfolderOnce(sf.id)
                    parseReasoningLogItems(
                        subfolderId = sf.id,
                        subfolderName = sf.name,
                        content = note?.content.orEmpty(),
                        fallbackSortKey = sf.updatedAt,
                    )
                }
                .sortedByDescending { it.sortKey }
            logs = merged
        }
    }

    if (selectedLog != null) {
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
                TextButton(onClick = { selectedLog = null }) {
                    Text("Back", color = colors.textMid, fontFamily = DmSansFamily)
                }
                Text(
                    text = "Reasoning Log",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp,
                )
            }

            val scrollState = androidx.compose.foundation.rememberScrollState()
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
                    tv.text = selectedLog?.fullContent.orEmpty()
                    tv.setTextColor(colors.textPrimary.toArgb())
                },
            )
        }
        return
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
                text = "Reasoning",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
            )
        }

        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No reasoning logs yet",
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
                items(logs, key = { it.id }) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.borderSoft, RoundedCornerShape(12.dp))
                            .clickable { selectedLog = item }
                            .padding(10.dp),
                    ) {
                        Text(
                            text = listOfNotNull(item.subfolderName, item.timestamp).joinToString(" • "),
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                        )
                        Text(
                            text = item.title,
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = item.preview,
                            color = colors.textMid,
                            fontFamily = DmSansFamily,
                            fontSize = 13.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun parseReasoningLogItems(
    subfolderId: Long,
    subfolderName: String,
    content: String,
    fallbackSortKey: Long,
): List<ReasoningLogItem> {
    if (content.isBlank()) return emptyList()
    val json = Json { ignoreUnknownKeys = true }
    val structuredLines = content.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("ABR1|") }
        .toList()
    if (structuredLines.isNotEmpty()) {
        val records = structuredLines.mapNotNull { line ->
            runCatching {
                val obj = json.parseToJsonElement(line.removePrefix("ABR1|")).jsonObject
                val type = obj["type"]?.jsonPrimitive?.content.orEmpty()
                val runId = obj["run_id"]?.jsonPrimitive?.content.orEmpty()
                ReasoningStructuredRecord(
                    type = type,
                    runId = runId.ifBlank { "unknown" },
                    timestamp = obj["timestamp"]?.jsonPrimitive?.content,
                    obj = obj,
                )
            }.getOrNull()
        }

        val groupedByRun = records.groupBy { it.runId }
        return groupedByRun.entries.mapIndexed { index, (runId, runRecords) ->
            val start = runRecords.firstOrNull { it.type == "run_start" }
            val end = runRecords.firstOrNull { it.type == "run_end" }
            val steps = runRecords.filter { it.type == "step" }
            val exitReason = end?.obj?.get("exit_reason")?.jsonPrimitive?.content.orEmpty()
            val startedAt = start?.timestamp
            val endedAt = end?.timestamp
            val fullTranscript = buildReasoningRunTranscript(
                runId = runId,
                start = start,
                steps = steps,
                end = end,
            )
            val preview = buildString {
                append("steps=${steps.size}")
                if (exitReason.isNotBlank()) append(" • exit=$exitReason")
                if (!startedAt.isNullOrBlank()) append(" • started=$startedAt")
            }
            ReasoningLogItem(
                id = "${subfolderId}_run_${runId}_$index",
                subfolderName = subfolderName,
                timestamp = endedAt ?: startedAt,
                title = "Run $runId",
                preview = preview,
                fullContent = fullTranscript,
                sortKey = fallbackSortKey - index,
            )
        }
    }

    return content.split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapIndexed { index, chunk ->
            val first = chunk.lineSequence().firstOrNull().orEmpty()
            ReasoningLogItem(
                id = "${subfolderId}_legacy_${index}_${chunk.hashCode()}",
                subfolderName = subfolderName,
                timestamp = null,
                title = first.take(80).ifBlank { "Legacy reasoning entry" },
                preview = chunk.take(180),
                fullContent = chunk,
                sortKey = fallbackSortKey - index,
            )
        }
}

private fun buildReasoningRunTranscript(
    runId: String,
    start: ReasoningStructuredRecord?,
    steps: List<ReasoningStructuredRecord>,
    end: ReasoningStructuredRecord?,
): String {
    return buildString {
        append("AgentByte Transcript\n")
        append("run_id=$runId\n")
        start?.timestamp?.let { append("started_at=$it\n") }
        val promptLabel = start?.obj?.get("prompt_label")?.jsonPrimitive?.content.orEmpty()
        if (promptLabel.isNotBlank()) append("prompt=\"$promptLabel\"\n")
        append("\n")

        steps.forEachIndexed { idx, step ->
            val obj = step.obj
            val calledTool = obj["called_tool_name"]?.jsonPrimitive?.content.orEmpty()
            val calledToolArgs = obj["called_tool_arguments"]?.jsonPrimitive?.content.orEmpty()
            val decisionRequired = obj["decision_required"]?.jsonPrimitive?.booleanOrNull ?: true
            val decisionParseFailed = obj["decision_parse_failed"]?.jsonPrimitive?.booleanOrNull ?: false
            val decisionRepairRetryAttempted = obj["decision_repair_retry_attempted"]?.jsonPrimitive?.booleanOrNull ?: false
            val decisionRepairRetrySucceeded = obj["decision_repair_retry_succeeded"]?.jsonPrimitive?.booleanOrNull ?: false
            val decisionSource = if (decisionRequired) "llm" else "orchestrator"
            val stateBefore = obj["state_before"]?.jsonPrimitive?.content.orEmpty()
            val stateAfter = obj["state_after"]?.jsonPrimitive?.content.orEmpty()
            val stateDeltaCount = obj["state_delta"]?.jsonObject?.size ?: 0
            append("Iteration ${obj["iteration"]?.jsonPrimitive?.content.orEmpty()} (step ${idx + 1})\n")
            step.timestamp?.let { append("timestamp=$it\n") }
            append("Selected: ${obj["selected_piece"]?.jsonPrimitive?.content.orEmpty()} (${obj["selected_role"]?.jsonPrimitive?.content.orEmpty()})\n")
            append("Tool: ${calledTool.ifBlank { "none" }}\n")
            append("Outcome: ${obj["outcome"]?.jsonPrimitive?.content.orEmpty()}\n")
            append("Decision:\n")
            append("  decision_required=$decisionRequired\n")
            append("  decision_source=$decisionSource\n")
            append("  selected_tool=${calledTool.ifBlank { "none" }}\n")
            append("  arguments_present=${calledToolArgs.isNotBlank()}\n")
            append("  decision_parse_failed=$decisionParseFailed\n")
            append("  decision_repair_retry_attempted=$decisionRepairRetryAttempted\n")
            append("  decision_repair_retry_succeeded=$decisionRepairRetrySucceeded\n")
            append("State snapshot:\n")
            append("  state_before=${if (stateBefore.isNotBlank()) "present" else "absent"} (chars=${stateBefore.length})\n")
            append("  state_after=${if (stateAfter.isNotBlank()) "present" else "absent"} (chars=${stateAfter.length})\n")
            append("  state_delta=$stateDeltaCount\n")
            append("  full_state_payload=available_in_ABR1_JSON\n")
            append("Prompt packet:\n")
            val prompt = obj["prompt_packet"]?.jsonObject
            if (prompt != null) {
                append("  mode=${prompt["mode"]?.jsonPrimitive?.content.orEmpty()}\n")
                append("  description=${prompt["description"]?.jsonPrimitive?.content.orEmpty()}\n")
                val opening = prompt["recommended_opening_tools"]?.jsonArray
                    ?.joinToString(",") { it.jsonPrimitive.content }
                    .orEmpty()
                append("  recommended_opening_tools=$opening\n")
                val allowed = prompt["allowed_tools"]?.jsonArray
                    ?.joinToString(",") { it.jsonPrimitive.content }
                    .orEmpty()
                append("  allowed_tools=$allowed\n")
            } else {
                append("  (none)\n")
            }
            append("LLM response (full):\n")
            val llm = obj["llm_response_text"]?.jsonPrimitive?.content.orEmpty()
            append(if (llm.isNotBlank()) llm else "(empty)")
            if (llm.isBlank() && calledTool.isNotBlank()) {
                append("\nLLM decision text captured in ABR1 (not shown in compact view).")
            }
            append("\n\n")
        }

        if (end != null) {
            append("Exit: ${end.obj["exit_reason"]?.jsonPrimitive?.content.orEmpty()}\n")
            append("iterations=${end.obj["iterations"]?.jsonPrimitive?.content.orEmpty()}\n")
            end.timestamp?.let { append("ended_at=$it\n") }
        }
    }
}

@Composable
private fun EidosSystemInboxListScreen(
    kind: EidosSystemKind,
    onBack: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as OptimalXApplication
    var entries by remember(kind) { mutableStateOf<List<MemoryNoteEntry>>(emptyList()) }

    LaunchedEffect(kind) {
        val parent = app.database.parentFolderDao().getSystemFolderByName(kind.systemFolderName)
        if (parent == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        app.database.subfolderDao().getAllActiveByParent(parent.id).collect { subfolders ->
            val parsed = subfolders
                .filter { it.deletedAt == null }
                .flatMap { sf ->
                    val note = app.database.noteDao().getBySubfolderOnce(sf.id)
                    parseMemoryNoteEntries(
                        subfolderId = sf.id,
                        subfolderName = sf.name,
                        noteContent = note?.content.orEmpty(),
                        fallbackUpdatedAt = sf.updatedAt,
                    )
                }
                .sortedByDescending { it.sortKey }
            entries = parsed
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
                text = kind.title,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
            )
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
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.borderSoft, RoundedCornerShape(12.dp))
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
            }
        }
    }
}

private fun parseMemoryNoteEntries(
    subfolderId: Long,
    subfolderName: String,
    noteContent: String,
    fallbackUpdatedAt: Long,
): List<MemoryNoteEntry> {
    if (noteContent.isBlank()) return emptyList()
    val dayStartMillis = parseSubfolderDayStartMillis(subfolderName)
    return noteContent
        .split(Regex("\n\\s*\n"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapIndexed { index, chunk ->
            val firstLine = chunk.lineSequence().firstOrNull().orEmpty().trim()
            val tsMatch = memoryLineTimestampRegex.find(firstLine)
            val parsedMillis = tsMatch?.groupValues
                ?.getOrNull(1)
                ?.let { raw -> runCatching { raw.toLocalDateTimeAtSystemZoneMillis() }.getOrNull() }
            val resolvedMillis = parsedMillis ?: dayStartMillis ?: fallbackUpdatedAt
            val resolvedTimestampLabel = parsedMillis?.let {
                memoryTimestampOutputFormatter.format(
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                )
            }
            val cleanedContent = if (tsMatch != null && tsMatch.groupValues.size >= 3) {
                val firstLineWithoutTimestamp = tsMatch.groupValues[2].trim()
                val remainder = chunk.lineSequence().drop(1).joinToString("\n")
                listOf(firstLineWithoutTimestamp, remainder)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            } else {
                chunk
            }
            MemoryNoteEntry(
                id = "${subfolderId}_${index}_${cleanedContent.hashCode()}",
                subfolderName = subfolderName,
                timestampLabel = resolvedTimestampLabel,
                content = cleanedContent,
                sortKey = resolvedMillis,
            )
        }
}

private fun parseSubfolderDayStartMillis(subfolderName: String): Long? {
    return runCatching {
        val day = java.time.LocalDate.parse(subfolderName)
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}

private suspend fun buildEidosIndexTree(app: OptimalXApplication): EidosIndexTree {
    // ON HOLD — index tree for Eidos Index UI/export; see EidosIndexFeature.
    val db = app.database
    val json = Json { ignoreUnknownKeys = true }
    val parents = db.parentFolderDao().getAllActive().first()
        .filter { it.deletedAt == null && !it.isSystemFolder }
        .sortedBy { it.createdAt }

    val parentNodes = parents.map { parent ->
        val allSubfolders = db.subfolderDao().getAllByParentOnce(parent.id)
            .filter { it.deletedAt == null }
        val userSubfolders = allSubfolders
            .filter { !it.isSystemSubfolder }
            .sortedBy { it.createdAt }
        val cacheSubfolder = allSubfolders.firstOrNull {
            it.isSystemSubfolder &&
                (it.name == SystemFolderNames.PARENT_MEMORY_CACHE_SUBFOLDER || it.name == "__memory_cache__")
        }
        val cacheMap = cacheSubfolder
            ?.let { db.noteDao().getBySubfolderOnce(it.id)?.content.orEmpty() }
            .orEmpty()
            .let { decodeMemoryCacheMap(it, json) }

        val subfolderNodes = userSubfolders.map { subfolder ->
            val note = db.noteDao().getBySubfolderOnce(subfolder.id)
            val files = db.fileReferenceDao().getBySubfolderOnce(subfolder.id)
            val chats = db.conversationDao().getAllBySubfolder(subfolder.id)
            EidosSubfolderNode(
                branch = semanticNode(
                    ref = "subfolder:${subfolder.id}",
                    objectType = "subfolder",
                    hintSeed = subfolder.name,
                    objectName = subfolder.name,
                    parentFolderName = parent.name,
                    subfolderName = subfolder.name,
                ),
                note = note?.let {
                    semanticNode(
                        ref = "note:${it.id}",
                        objectType = "note",
                        hintSeed = it.content,
                        objectName = subfolder.name,
                        parentFolderName = parent.name,
                        subfolderName = subfolder.name,
                    )
                },
                files = files.map { file ->
                    semanticNode(
                        ref = "file:${file.id}",
                        objectType = "file",
                        hintSeed = listOf(file.fileName, file.fileType).joinToString(" ").trim(),
                        objectName = file.fileName,
                        parentFolderName = parent.name,
                        subfolderName = subfolder.name,
                    )
                },
                subfolderChats = chats.map { chat ->
                    semanticNode(
                        ref = "chat:subfolder:${subfolder.id}:${chat.id}",
                        objectType = "chat_subfolder",
                        hintSeed = chat.title,
                        objectName = chat.title.ifBlank { "Chat ${chat.id}" },
                        parentFolderName = parent.name,
                        subfolderName = subfolder.name,
                    )
                },
                subfolderMemoryCache = cacheMap[subfolder.id]?.let { cacheText ->
                    semanticNode(
                        ref = "cache:subfolder:${subfolder.id}",
                        objectType = "cache",
                        hintSeed = cacheText,
                        objectName = "Memory cache",
                        parentFolderName = parent.name,
                        subfolderName = subfolder.name,
                    )
                },
            )
        }
        val parentChats = db.conversationDao().getAllByParentFolder(parent.id).map { chat ->
            semanticNode(
                ref = "chat:parent:${parent.id}:${chat.id}",
                objectType = "chat_parent",
                hintSeed = chat.title,
                objectName = chat.title.ifBlank { "Chat ${chat.id}" },
                parentFolderName = parent.name,
            )
        }
        EidosParentNode(
            branch = semanticNode(
                ref = "parent:${parent.id}",
                objectType = "parent",
                hintSeed = parent.name,
                objectName = parent.name,
            ),
            subfolders = subfolderNodes,
            parentChats = parentChats,
        )
    }

    val generalChats = db.conversationDao().getAllGeneral().map { chat ->
        semanticNode(
            ref = "chat:general:${chat.id}",
            objectType = "chat_general",
            hintSeed = chat.title,
            objectName = chat.title.ifBlank { "Chat ${chat.id}" },
            parentFolderName = SystemFolderNames.EIDOS_CHATS,
        )
    }

    val journal = systemEntries(db = db, parentName = SystemFolderNames.EIDOS_JOURNAL, objectType = "journal")
    val quickNotes = quickNoteEntries(db = db)
    val ltm = systemEntries(db = db, parentName = SystemFolderNames.EIDOS_MEMORY, objectType = "ltm")

    return EidosIndexTree(
        parents = parentNodes,
        generalChats = generalChats,
        journal = journal,
        quickNotes = quickNotes,
        ltm = ltm,
    )
}

private suspend fun buildEidosIndexPayload(app: OptimalXApplication): String {
    val model = buildEidosIndexTree(app)
    val payload = buildJsonObject {
        put(
            "hierarchy",
            buildJsonObject {
                put(
                    "parents",
                    buildJsonArray {
                        model.parents.forEach { parent ->
                            add(
                                buildJsonObject {
                                    putIndexNode(parent.branch)
                                    put(
                                        "children",
                                        buildJsonObject {
                                            put(
                                                "subfolders",
                                                buildJsonArray {
                                                    parent.subfolders.forEach { subfolder ->
                                                        add(
                                                            buildJsonObject {
                                                                putIndexNode(subfolder.branch)
                                                                put(
                                                                    "children",
                                                                    buildJsonObject {
                                                                        subfolder.note?.let { note ->
                                                                            put("note", buildJsonObject { putIndexNode(note) })
                                                                        }
                                                                        put(
                                                                            "files",
                                                                            buildJsonArray {
                                                                                subfolder.files.forEach { file ->
                                                                                    add(buildJsonObject { putIndexNode(file) })
                                                                                }
                                                                            },
                                                                        )
                                                                        put(
                                                                            "subfolder_chat_conversations",
                                                                            buildJsonArray {
                                                                                subfolder.subfolderChats.forEach { chat ->
                                                                                    add(buildJsonObject { putIndexNode(chat) })
                                                                                }
                                                                            },
                                                                        )
                                                                        subfolder.subfolderMemoryCache?.let { cache ->
                                                                            put(
                                                                                "subfolder_memory_cache",
                                                                                buildJsonObject { putIndexNode(cache) },
                                                                            )
                                                                        }
                                                                    },
                                                                )
                                                            },
                                                        )
                                                    }
                                                },
                                            )
                                            put(
                                                "parent_chat_conversations",
                                                buildJsonArray {
                                                    parent.parentChats.forEach { chat ->
                                                        add(buildJsonObject { putIndexNode(chat) })
                                                    }
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            },
        )
        put(
            "chats_general",
            buildJsonObject {
                put(
                    "general_chat_conversations",
                    buildJsonArray {
                        model.generalChats.forEach { chat ->
                            add(buildJsonObject { putIndexNode(chat) })
                        }
                    },
                )
            },
        )
        put(
            "journal",
            buildJsonObject {
                put(
                    "entries",
                    buildJsonArray {
                        model.journal.forEach { entry ->
                            add(buildJsonObject { putIndexNode(entry) })
                        }
                    },
                )
            },
        )
        put(
            "quick_notes",
            buildJsonObject {
                put(
                    "entries",
                    buildJsonArray {
                        model.quickNotes.forEach { entry ->
                            add(buildJsonObject { putIndexNode(entry) })
                        }
                    },
                )
            },
        )
        put(
            "ltm",
            buildJsonObject {
                put(
                    "entries",
                    buildJsonArray {
                        model.ltm.forEach { entry ->
                            add(buildJsonObject { putIndexNode(entry) })
                        }
                    },
                )
            },
        )
    }
    return Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putIndexNode(node: EidosIndexNode) {
    put("ref", JsonPrimitive(node.ref))
    put("tag", JsonPrimitive(node.tag))
    put("hint", JsonPrimitive(node.hint))
    put("objectName", JsonPrimitive(node.objectName))
    put("parentFolderName", node.parentFolderName?.let(::JsonPrimitive) ?: JsonNull)
    put("subfolderName", node.subfolderName?.let(::JsonPrimitive) ?: JsonNull)
}

private suspend fun systemEntries(
    db: com.example.optimalx.data.db.AppDatabase,
    parentName: String,
    objectType: String,
): List<EidosIndexNode> {
    val parent = db.parentFolderDao().getSystemFolderByName(parentName) ?: return emptyList()
    return db.subfolderDao().getAllByParentOnce(parent.id)
        .filter { it.deletedAt == null }
        .sortedByDescending { it.updatedAt }
        .mapNotNull { subfolder ->
            val note = db.noteDao().getBySubfolderOnce(subfolder.id) ?: return@mapNotNull null
            if (note.content.isBlank()) return@mapNotNull null
            val ref = when (objectType) {
                "journal" -> "journal:${subfolder.name}"
                else -> "ltm:${subfolder.name}"
            }
            semanticNode(
                ref = ref,
                objectType = objectType,
                hintSeed = note.content,
                objectName = subfolder.name,
                parentFolderName = parentName,
            )
        }
}

private suspend fun quickNoteEntries(db: com.example.optimalx.data.db.AppDatabase): List<EidosIndexNode> {
    val parent = db.parentFolderDao().getSystemFolderByName(SystemFolderNames.QUICK_NOTES) ?: return emptyList()
    val out = mutableListOf<EidosIndexNode>()
    db.subfolderDao().getAllByParentOnce(parent.id)
        .filter { it.deletedAt == null && !it.isSystemSubfolder }
        .forEach { daySubfolder ->
            val note = db.noteDao().getBySubfolderOnce(daySubfolder.id) ?: return@forEach
            val blocks = note.content
                .split(Regex("\n\\s*\n"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            blocks.forEachIndexed { idx, block ->
                out += semanticNode(
                    ref = "quick_note:${daySubfolder.name}:${idx + 1}",
                    objectType = "quick_note",
                    hintSeed = block,
                    objectName = "Quick note ${idx + 1}",
                    parentFolderName = SystemFolderNames.QUICK_NOTES,
                    subfolderName = daySubfolder.name,
                )
            }
        }
    return out
}

private fun semanticNode(
    ref: String,
    objectType: String,
    hintSeed: String,
    objectName: String,
    parentFolderName: String? = null,
    subfolderName: String? = null,
): EidosIndexNode {
    val normalized = normalizeIndexHint(hintSeed)
    val tag = when {
        objectName.isNotBlank() && objectName.length <= 48 -> objectName
        else -> normalized.split(Regex("\\s+")).filter { it.isNotBlank() }.take(4).joinToString(" ").take(48)
    }.ifBlank { objectType.replace('_', ' ') }
    val hint = normalized.ifBlank { objectName.ifBlank { "Untitled" } }
    return EidosIndexNode(
        ref = ref,
        tag = tag,
        hint = hint,
        objectName = objectName.ifBlank { tag },
        parentFolderName = parentFolderName,
        subfolderName = subfolderName,
    )
}

private fun normalizeIndexHint(raw: String): String {
    val normalized = raw
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (normalized.isBlank()) "Untitled" else normalized.take(180)
}

private fun decodeMemoryCacheMap(content: String, json: Json): Map<Long, String> {
    if (content.isBlank()) return emptyMap()
    val parsed = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return emptyMap()
    return buildMap {
        parsed.forEach { (k, v) ->
            val id = k.toLongOrNull() ?: return@forEach
            val value = (v as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (value.isNotBlank()) put(id, value)
        }
    }
}

private fun String.toLocalDateTimeAtSystemZoneMillis(): Long {
    val parsed = java.time.LocalDateTime.parse(this, memoryTimestampInputFormatter)
    return parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
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
            // Native TextView: reliable selection/copy for read-only system notes (e.g. Eidos Reasoning).
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
