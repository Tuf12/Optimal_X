package com.example.optimalx.ui.eidos

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.OptimalXApplication
import com.example.optimalx.data.model.EidosApiTraceDirectorySummary
import com.example.optimalx.data.model.EidosApiTraceRound
import com.example.optimalx.data.model.EidosApiTraceRun
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

object EidosApiTraceRoutes {
    const val DIRECTORIES = "eidos_api_trace"
    const val RUNS = "eidos_api_trace_runs/{directoryKey}"
    const val RUN_DETAIL = "eidos_api_trace_run/{runId}"

    fun runs(directoryKey: String): String =
        "eidos_api_trace_runs/${Uri.encode(directoryKey, Charsets.UTF_8.name())}"

    fun runDetail(runId: Long): String = "eidos_api_trace_run/$runId"
}

class EidosApiTraceDirectoriesViewModel(app: Application) : ViewModel() {
    private val dao = (app as OptimalXApplication).database.eidosApiTraceDao()
    private val _directories = MutableStateFlow<List<EidosApiTraceDirectorySummary>>(emptyList())
    val directories: StateFlow<List<EidosApiTraceDirectorySummary>> = _directories.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _directories.value = dao.listDirectorySummaries()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            dao.deleteAllRuns()
            refresh()
        }
    }
}

class EidosApiTraceRunsViewModel(
    app: Application,
    private val directoryKey: String,
) : ViewModel() {
    private val dao = (app as OptimalXApplication).database.eidosApiTraceDao()
    private val _runs = MutableStateFlow<List<EidosApiTraceRun>>(emptyList())
    val runs: StateFlow<List<EidosApiTraceRun>> = _runs.asStateFlow()
    val directoryLabel: String
        get() = _runs.value.firstOrNull()?.directoryLabel ?: directoryKey

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _runs.value = dao.listRunsForDirectory(directoryKey)
        }
    }

    fun clearDirectory() {
        viewModelScope.launch {
            dao.deleteRunsInDirectory(directoryKey)
            refresh()
        }
    }
}

class EidosApiTraceRunDetailViewModel(
    app: Application,
    private val runId: Long,
) : ViewModel() {
    private val dao = (app as OptimalXApplication).database.eidosApiTraceDao()
    private val _run = MutableStateFlow<EidosApiTraceRun?>(null)
    val run: StateFlow<EidosApiTraceRun?> = _run.asStateFlow()
    private val _rounds = MutableStateFlow<List<EidosApiTraceRound>>(emptyList())
    val rounds: StateFlow<List<EidosApiTraceRound>> = _rounds.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _run.value = dao.getRun(runId)
            _rounds.value = dao.listRoundsForRun(runId)
        }
    }

    fun deleteRun() {
        viewModelScope.launch {
            dao.deleteRun(runId)
            _run.value = null
            _rounds.value = emptyList()
        }
    }
}

@Composable
fun EidosApiTraceDirectoriesScreen(
    onBack: () -> Unit,
    onOpenDirectory: (directoryKey: String) -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val viewModel: EidosApiTraceDirectoriesViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                EidosApiTraceDirectoriesViewModel(app as Application)
            }
        },
    )
    val directories by viewModel.directories.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all API traces?") },
            text = { Text("Deletes every stored trace run on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearAll()
                }) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        TraceTopBar(
            title = "API Trace",
            subtitle = "Directories · newest activity first",
            onBack = onBack,
            actionLabel = if (directories.isNotEmpty()) "Clear all" else null,
            onAction = { showClearDialog = true },
        )
        if (directories.isEmpty()) {
            Text(
                text = "No traces yet. Enable capture in Settings → Developer, then send an Eidos message.",
                color = colors.textDim,
                fontFamily = DmSansFamily,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(directories, key = { it.directoryKey }) { dir ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surface2, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .clickable { onOpenDirectory(dir.directoryKey) }
                            .padding(12.dp),
                    ) {
                        Text(
                            text = dir.directoryLabel,
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = "${dir.runCount} run(s) · last ${formatTraceTime(dir.lastStartedAtMillis)}",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EidosApiTraceRunsScreen(
    directoryKey: String,
    onBack: () -> Unit,
    onOpenRun: (Long) -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val viewModel: EidosApiTraceRunsViewModel = viewModel(
        key = "api_trace_runs_$directoryKey",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                EidosApiTraceRunsViewModel(app as Application, directoryKey)
            }
        },
    )
    val runs by viewModel.runs.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear this directory?") },
            text = { Text("Deletes all trace runs for ${viewModel.directoryLabel}.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearDirectory()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        TraceTopBar(
            title = viewModel.directoryLabel,
            subtitle = "Runs · newest first",
            onBack = onBack,
            actionLabel = if (runs.isNotEmpty()) "Clear" else null,
            onAction = { showClearDialog = true },
        )
        if (runs.isEmpty()) {
            Text(
                text = "No runs in this directory.",
                color = colors.textDim,
                fontFamily = DmSansFamily,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(runs, key = { it.id }) { run ->
                    TraceRunCard(run = run, onClick = { onOpenRun(run.id) })
                }
            }
        }
    }
}

@Composable
fun EidosApiTraceRunDetailScreen(
    runId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val clipboard = LocalClipboardManager.current
    val viewModel: EidosApiTraceRunDetailViewModel = viewModel(
        key = "api_trace_run_$runId",
        factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                EidosApiTraceRunDetailViewModel(app as Application, runId)
            }
        },
    )
    val run by viewModel.run.collectAsState()
    val rounds by viewModel.rounds.collectAsState()
    var expandedRoundIndex by remember { mutableStateOf(0) }
    var showTab by remember { mutableStateOf("request") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        TraceTopBar(
            title = run?.conversationTitle ?: "Trace run",
            subtitle = run?.let {
                "${it.provider} · ${it.roundCount} round(s) · ${it.status}"
            } ?: "Loading…",
            onBack = onBack,
            actionLabel = if (run != null) "Delete" else null,
            onAction = {
                viewModel.deleteRun()
                onDeleted()
            },
        )
        val activeRun = run ?: return@Column
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "run_meta") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "User: ${activeRun.userMessagePreview}",
                        color = colors.textMid,
                        fontFamily = DmSansFamily,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = "Started ${formatTraceTime(activeRun.startedAtMillis)} · scope ${activeRun.scopeType}",
                        color = colors.textDim,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                    )
                    if (activeRun.conversationId != null) {
                        Text(
                            text = "Conversation #${activeRun.conversationId}",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            if (rounds.isEmpty()) {
                item(key = "no_rounds") {
                    Text(
                        text = "No provider rounds recorded.",
                        color = colors.textDim,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            } else {
                items(rounds, key = { it.id }) { round ->
                    val isExpanded = expandedRoundIndex == round.roundIndex
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surface2, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .clickable {
                                expandedRoundIndex = if (isExpanded) -1 else round.roundIndex
                                showTab = "request"
                            }
                            .padding(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Round ${round.roundIndex + 1} · ${round.phase}",
                                color = colors.textPrimary,
                                fontFamily = DmSansFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                            )
                            if (isExpanded) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { showTab = "request" }) {
                                        Text(
                                            "Request",
                                            color = if (showTab == "request") colors.accent else colors.textDim,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    TextButton(onClick = { showTab = "response" }) {
                                        Text(
                                            "Response",
                                            color = if (showTab == "response") colors.accent else colors.textDim,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val text = if (showTab == "request") {
                                                round.requestJson
                                            } else {
                                                round.responseJson
                                            }
                                            clipboard.setText(AnnotatedString(text))
                                        },
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copy",
                                            tint = colors.textMid,
                                        )
                                    }
                                }
                            }
                        }
                        if (isExpanded) {
                            val body = if (showTab == "request") round.requestJson else round.responseJson
                            Text(
                                text = body,
                                color = colors.textMid,
                                fontFamily = DmMonoFamily,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalOptimalXColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textMid)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = colors.accent, fontFamily = DmSansFamily, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TraceRunCard(run: EidosApiTraceRun, onClick: () -> Unit) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(
            text = run.conversationTitle.ifBlank { "Eidos" },
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = run.userMessagePreview,
            color = colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "${formatTraceTime(run.startedAtMillis)} · ${run.provider} · ${run.roundCount} round(s) · ${run.status}",
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun formatTraceTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
