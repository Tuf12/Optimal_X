package com.example.optimalx.ui.link

// Spec: app/docs/architecture/OPTIMALX_LINK.md §phone-link-screen.
//
// User-facing screen for OptimalX Link. While this screen is in the foreground
// the embedded Ktor server runs. Leaving the screen (back, navigation, app
// background) tears the server down — there is intentionally no foreground
// service.

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.data.backup.OptimalXBackupManager
import com.example.optimalx.data.link.DesktopCallResult
import com.example.optimalx.data.link.DesktopClient
import com.example.optimalx.data.link.DesktopEndpoint
import com.example.optimalx.data.link.DesktopListResult
import com.example.optimalx.data.link.DesktopPingResult
import com.example.optimalx.data.link.DesktopSnapshotEntry
import com.example.optimalx.data.link.LinkAuthToken
import com.example.optimalx.data.link.LinkQrEncoder
import com.example.optimalx.data.link.LinkServer
import com.example.optimalx.data.link.LinkServerStatus
import com.example.optimalx.data.link.LocalNetworkInfo
import com.example.optimalx.data.link.RestoreJob
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LinkScreen(
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val colors = LocalOptimalXColors.current
    val context = LocalContext.current
    val appVersionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    val server = remember { LinkServer(context = context, appVersionName = appVersionName) }
    val desktopClient = remember {
        DesktopClient(context = context, appVersionName = appVersionName)
    }
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var networkSnapshot by remember {
        mutableStateOf(LocalNetworkInfo.current(context))
    }
    // Connect-to-desktop form fields. Not persisted to disk yet (Phase 5 will
    // surface a "remember this desktop" toggle on the Settings → Backup pane).
    var desktopHost by rememberSaveable { mutableStateOf("") }
    var desktopPort by rememberSaveable { mutableStateOf("17833") }
    var desktopToken by rememberSaveable { mutableStateOf("") }
    var desktopActivityLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var desktopSnapshots by remember { mutableStateOf<List<DesktopSnapshotEntry>>(emptyList()) }
    var desktopBusy by remember { mutableStateOf(false) }

    fun logDesktop(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        desktopActivityLog = (listOf("$stamp  $message") + desktopActivityLog).take(20)
    }

    val desktopEndpoint = remember(desktopHost, desktopPort, desktopToken) {
        runCatching {
            DesktopEndpoint(
                host = desktopHost.trim(),
                port = desktopPort.trim().ifEmpty { "17833" }.toInt(),
                token = desktopToken.trim(),
            )
        }.getOrNull()
    }

    // Poll the active network every 2s so swapping Wi-Fi networks while the
    // screen is open refreshes the displayed IP automatically.
    LaunchedEffect(Unit) {
        while (true) {
            networkSnapshot = LocalNetworkInfo.current(context)
            delay(2_000)
        }
    }

    // Start / stop the embedded server with the screen lifecycle. The Wi-Fi-only
    // toggle gates startup: on cellular with the toggle ON, the server stays off.
    val networkAllows by remember(networkSnapshot, wifiOnly) {
        derivedStateOf {
            when {
                !wifiOnly -> true
                networkSnapshot.connection == LocalNetworkInfo.Connection.WIFI -> true
                else -> false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { server.stop() }
    }

    LaunchedEffect(networkAllows) {
        if (networkAllows) server.start() else server.stop()
    }

    val state by server.state.collectAsState()
    val pendingConfirm by server.restoreJobs.pendingConfirm.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.textPrimary,
                    )
                }
                Text(
                    text = "OptimalX Link",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LinkSection(title = "Server") {
                    StatusRow(state.status, networkSnapshot.connection)
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow(
                        key = "Address",
                        value = if (networkSnapshot.ipv4.isNotEmpty())
                            "${networkSnapshot.ipv4}:${state.port}"
                        else
                            "no local network",
                    )
                    KeyValueRow(
                        key = "Status",
                        value = state.status.toReadable(),
                    )
                    state.lastError?.let { err ->
                        KeyValueRow(key = "Error", value = err)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Wi-Fi only",
                            color = colors.textPrimary,
                            fontFamily = DmSansFamily,
                            fontSize = 13.sp,
                        )
                    }
                    Text(
                        text = "When off, the server may bind on cellular. Other devices on the same NAT can reach it; the carrier may not allow inbound connections.",
                        color = colors.textDim,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                    )
                }

                LinkSection(title = "Pairing") {
                    val tokenDisplay = when {
                        state.token.isEmpty() -> "—"
                        showToken -> state.token
                        else -> "•".repeat(state.token.length.coerceAtMost(32))
                    }
                    KeyValueRow(
                        key = "Token",
                        value = tokenDisplay,
                        monospace = true,
                    )
                    Row {
                        ActionLink(
                            text = if (showToken) "Hide token" else "Show token",
                            onClick = { showToken = !showToken },
                            enabled = state.token.isNotEmpty(),
                        )
                        Spacer(Modifier.width(12.dp))
                        ActionLink(
                            text = "Copy token",
                            onClick = {
                                val clip = context
                                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                clip?.setPrimaryClip(
                                    ClipData.newPlainText("OptimalX Link token", state.token),
                                )
                            },
                            enabled = state.token.isNotEmpty(),
                        )
                        Spacer(Modifier.width(12.dp))
                        ActionLink(
                            text = "Regenerate",
                            onClick = { server.regenerateToken() },
                            enabled = state.status == LinkServerStatus.LISTENING,
                        )
                    }

                    if (state.token.isNotEmpty() && networkSnapshot.ipv4.isNotEmpty()) {
                        val pairingPayload = remember(state.token, networkSnapshot.ipv4, state.port) {
                            "optimalx-link://${networkSnapshot.ipv4}:${state.port}?token=${state.token}"
                        }
                        Spacer(Modifier.height(8.dp))
                        QrBlock(payload = pairingPayload)
                        Text(
                            text = pairingPayload,
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        )
                    }
                }

                LinkSection(title = "Endpoints") {
                    Text(
                        text = "GET /v1/status",
                        color = colors.textPrimary,
                        fontFamily = DmMonoFamily,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = "JSON summary (app + db version, file counts).",
                        color = colors.textDim,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                    Text(
                        text = "GET /v1/bundles/full",
                        color = colors.textPrimary,
                        fontFamily = DmMonoFamily,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = "Streams the full snapshot .zip (manifest + db + workshop + attachments). All requests require Authorization: Bearer <token>.",
                        color = colors.textDim,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }

                LinkSection(title = "Connect to desktop") {
                    Text(
                        text = "Push the phone's current snapshot to a desktop running OptimalX Link, or import one back to here. Restores still require the confirm tap above.",
                        color = colors.textDim,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    DesktopField(
                        label = "Host",
                        value = desktopHost,
                        onValueChange = { desktopHost = it },
                        placeholder = "192.168.1.42",
                    )
                    DesktopField(
                        label = "Port",
                        value = desktopPort,
                        onValueChange = { desktopPort = it.filter { ch -> ch.isDigit() }.take(5) },
                        placeholder = "17833",
                        numeric = true,
                    )
                    DesktopField(
                        label = "Token",
                        value = desktopToken,
                        onValueChange = { desktopToken = it.trim() },
                        placeholder = "paste desktop bearer token",
                        monospace = true,
                    )

                    Row(modifier = Modifier.padding(top = 6.dp)) {
                        ActionLink(
                            text = "Test",
                            enabled = !desktopBusy && desktopEndpoint != null,
                            onClick = {
                                val ep = desktopEndpoint ?: return@ActionLink
                                desktopBusy = true
                                logDesktop("Pinging $ep…")
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) { desktopClient.ping(ep) }
                                    logDesktop(
                                        when (result) {
                                            is DesktopPingResult.Success ->
                                                "OK link=${result.status.linkVersion}, snapshots=${result.status.snapshotCount}"
                                            is DesktopPingResult.Failure -> "Ping failed: ${result.message}"
                                        }
                                    )
                                    desktopBusy = false
                                }
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        ActionLink(
                            text = "Push snapshot",
                            enabled = !desktopBusy && desktopEndpoint != null,
                            onClick = {
                                val ep = desktopEndpoint ?: return@ActionLink
                                desktopBusy = true
                                logDesktop("Pushing snapshot to ${ep.host}:${ep.port}…")
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        desktopClient.pushSnapshot(ep)
                                    }
                                    logDesktop(
                                        when (result) {
                                            is DesktopCallResult.Success -> "Push OK"
                                            is DesktopCallResult.Failure -> "Push failed: ${result.message}"
                                        }
                                    )
                                    desktopBusy = false
                                }
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        ActionLink(
                            text = "Refresh list",
                            enabled = !desktopBusy && desktopEndpoint != null,
                            onClick = {
                                val ep = desktopEndpoint ?: return@ActionLink
                                desktopBusy = true
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        desktopClient.listSnapshots(ep)
                                    }
                                    when (result) {
                                        is DesktopListResult.Success -> {
                                            desktopSnapshots = result.snapshots
                                            logDesktop("Got ${result.snapshots.size} snapshot(s)")
                                        }
                                        is DesktopListResult.Failure -> {
                                            logDesktop("List failed: ${result.message}")
                                        }
                                    }
                                    desktopBusy = false
                                }
                            },
                        )
                    }

                    if (desktopSnapshots.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "DESKTOP SNAPSHOTS",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        desktopSnapshots.forEach { entry ->
                            DesktopSnapshotRow(
                                entry = entry,
                                enabled = !desktopBusy && desktopEndpoint != null,
                                onImport = {
                                    val ep = desktopEndpoint ?: return@DesktopSnapshotRow
                                    desktopBusy = true
                                    logDesktop("Importing ${entry.id}…")
                                    coroutineScope.launch {
                                        val tempFile = File(
                                            context.cacheDir,
                                            "optimalx-link/desktop-${entry.id}.zip",
                                        )
                                        val download = withContext(Dispatchers.IO) {
                                            desktopClient.downloadSnapshot(ep, entry.id, tempFile)
                                        }
                                        when (download) {
                                            is DesktopCallResult.Failure -> {
                                                logDesktop("Download failed: ${download.message}")
                                                desktopBusy = false
                                                return@launch
                                            }
                                            is DesktopCallResult.Success -> Unit
                                        }
                                        val staging = File(
                                            context.cacheDir,
                                            "optimalx-link/desktop-${entry.id}/staging",
                                        ).apply { mkdirs() }
                                        val validation = withContext(Dispatchers.IO) {
                                            tempFile.inputStream().use { stream ->
                                                OptimalXBackupManager.validateZipForRestore(
                                                    input = stream,
                                                    stagingDir = staging,
                                                )
                                            }
                                        }
                                        // Convert the local download to a
                                        // pending-confirm job so the user has
                                        // to tap Accept here, same as any
                                        // inbound network restore.
                                        when (validation) {
                                            is com.example.optimalx.data.backup.RestoreValidation.Failed -> {
                                                logDesktop("Import validation failed: ${validation.message}")
                                                staging.deleteRecursively()
                                            }
                                            is com.example.optimalx.data.backup.RestoreValidation.Ready -> {
                                                val job = server.restoreJobs.beginValidating(
                                                    callerAddress = "desktop (${ep.host})",
                                                    rootForId = { _ ->
                                                        // The validator already wrote into `staging`,
                                                        // so the registry's "wipe on terminal" path
                                                        // points at the staging directory directly.
                                                        staging
                                                    },
                                                )
                                                server.restoreJobs.completeValidation(job.id, validation)
                                                logDesktop(
                                                    "Imported ${entry.id} — review the confirm dialog above.",
                                                )
                                            }
                                        }
                                        tempFile.delete()
                                        desktopBusy = false
                                    }
                                },
                            )
                        }
                    }

                    if (desktopActivityLog.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "DESKTOP ACTIVITY",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        desktopActivityLog.forEach { line ->
                            Text(
                                text = line,
                                color = colors.textPrimary,
                                fontFamily = DmMonoFamily,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                LinkSection(title = "Activity") {
                    if (state.activity.isEmpty()) {
                        Text(
                            text = "(no activity)",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                        )
                    } else {
                        state.activity.forEach { entry ->
                            ActivityRow(entry.epochMs, entry.message)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
            }
        }

        // Confirm overlay. Spec: app/docs/architecture/OPTIMALX_LINK.md §recovery-the-workshop-wipe-case.
        // Renders only when the registry has a PENDING_CONFIRM job. Every
        // inbound restore must be explicitly Accepted here — the rest of the
        // screen is intentionally still visible behind the scrim so the user
        // can cross-check that the IP and timestamp match what they expect.
        pendingConfirm?.let { job ->
            RestoreConfirmDialog(
                job = job,
                onAccept = { coroutineScope.launch { server.acceptRestore(job.id) } },
                onReject = { coroutineScope.launch { server.rejectRestore(job.id) } },
            )
        }
    }
}

@Composable
private fun StatusRow(
    status: LinkServerStatus,
    connection: LocalNetworkInfo.Connection,
) {
    val colors = LocalOptimalXColors.current
    val dotColor = when (status) {
        LinkServerStatus.LISTENING -> Color(0xFF4ADE80)
        LinkServerStatus.STARTING -> Color(0xFFFACC15)
        LinkServerStatus.ERROR -> Color(0xFFEF4444)
        LinkServerStatus.OFF -> colors.textDim
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = status.toReadable(),
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = when (connection) {
                LocalNetworkInfo.Connection.WIFI -> "Wi-Fi"
                LocalNetworkInfo.Connection.CELLULAR -> "cellular"
                LocalNetworkInfo.Connection.NONE -> "no network"
            },
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun KeyValueRow(
    key: String,
    value: String,
    monospace: Boolean = false,
) {
    val colors = LocalOptimalXColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = key,
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            color = colors.textPrimary,
            fontFamily = if (monospace) DmMonoFamily else DmSansFamily,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ActionLink(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalOptimalXColors.current
    Text(
        text = text,
        color = if (enabled) colors.accent else colors.textDim,
        fontFamily = DmMonoFamily,
        fontSize = 11.sp,
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .let { if (enabled) it.clickable { onClick() } else it }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun QrBlock(payload: String) {
    val colors = LocalOptimalXColors.current
    // Defensive: ZXing can throw for absurdly large inputs. Wrap in runCatching
    // so the screen stays usable if encoding fails for any reason.
    val matrix = remember(payload) {
        runCatching { LinkQrEncoder.encode(payload, size = 320) }.getOrNull()
    }
    if (matrix == null) {
        Text(
            text = "(QR unavailable)",
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 11.sp,
        )
        return
    }
    val rows = matrix.size
    val cols = matrix.firstOrNull()?.size ?: rows
    Box(
        modifier = Modifier
            .size(220.dp)
            .background(Color.White)
            .padding(8.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellW = size.width / cols
            val cellH = size.height / rows
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    if (matrix[y][x]) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x * cellW, y * cellH),
                            size = Size(cellW + 0.5f, cellH + 0.5f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(epochMs: Long, message: String) {
    val colors = LocalOptimalXColors.current
    val stamp = remember(epochMs) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(epochMs))
    }
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = stamp,
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 10.sp,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = message,
            color = colors.textPrimary,
            fontFamily = DmMonoFamily,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun LinkSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(
            text = title.uppercase(),
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        content()
    }
}

private fun LinkServerStatus.toReadable(): String = when (this) {
    LinkServerStatus.OFF -> "off"
    LinkServerStatus.STARTING -> "starting…"
    LinkServerStatus.LISTENING -> "listening"
    LinkServerStatus.ERROR -> "error"
}

/** Unused — kept so future Phase 3 can call [LinkAuthToken.generate] directly from UI. */
@Suppress("unused")
private val keepUnusedTokenRef = LinkAuthToken

@Composable
private fun DesktopField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    numeric: Boolean = false,
    monospace: Boolean = false,
) {
    val colors = LocalOptimalXColors.current
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.background)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.textPrimary,
                    fontFamily = if (monospace || numeric) DmMonoFamily else DmSansFamily,
                    fontSize = 13.sp,
                ),
                keyboardOptions = if (numeric) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = colors.textDim,
                            fontFamily = if (monospace || numeric) DmMonoFamily else DmSansFamily,
                            fontSize = 13.sp,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
private fun DesktopSnapshotRow(
    entry: DesktopSnapshotEntry,
    enabled: Boolean,
    onImport: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val stamp = remember(entry.epochMs) {
        if (entry.epochMs > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(entry.epochMs))
        } else {
            entry.id
        }
    }
    val sizeLabel = remember(entry.sizeBytes) { formatBytes(entry.sizeBytes) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stamp,
                color = colors.textPrimary,
                fontFamily = DmMonoFamily,
                fontSize = 12.sp,
            )
            Text(
                text = "app ${entry.appVersion.ifBlank { "?" }} · db v${entry.dbVersion} · $sizeLabel",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 10.sp,
            )
        }
        ActionLink(
            text = "Import",
            enabled = enabled,
            onClick = onImport,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var idx = 0
    var value = bytes.toDouble()
    while (value >= 1024.0 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    return String.format(Locale.US, "%.1f %s", value, units[idx])
}

/**
 * Modal scrim + card that the user must Accept or Reject. The whole screen
 * intentionally remains visible behind a translucent backdrop so the user
 * can compare the caller IP and snapshot timestamp against the desktop's
 * activity log before tapping anything.
 */
@Composable
private fun RestoreConfirmDialog(
    job: RestoreJob,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val callerLabel = job.callerAddress.ifBlank { "(unknown caller)" }
    val timestamp = remember(job.snapshotEpochMs) {
        if (job.snapshotEpochMs > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(job.snapshotEpochMs))
        } else {
            "(no timestamp in manifest)"
        }
    }
    val appLabel = job.manifest?.appVersionName.orEmpty().ifBlank { "(unknown app version)" }
    val dbLabel = job.manifest?.dbVersion?.takeIf { it > 0 }?.let { "v$it" } ?: "(unknown db version)"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(enabled = false) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "RESTORE REQUEST",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "A remote client wants to replace this phone's data with a snapshot.",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            KeyValueRow(key = "From", value = callerLabel, monospace = true)
            KeyValueRow(key = "Taken", value = timestamp)
            KeyValueRow(key = "App", value = appLabel, monospace = true)
            KeyValueRow(key = "DB", value = dbLabel, monospace = true)

            Spacer(Modifier.height(14.dp))
            Text(
                text = "This will REPLACE your current database, workshop, and attachments. " +
                    "A `.bak` copy of the current database is kept for one more app open.",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ConfirmButton(
                    label = "Reject",
                    accent = Color(0xFFEF4444),
                    onClick = onReject,
                )
                ConfirmButton(
                    label = "Accept",
                    accent = Color(0xFF4ADE80),
                    onClick = onAccept,
                )
            }
        }
    }
}

@Composable
private fun ConfirmButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.15f))
            .border(1.dp, accent, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 28.dp, vertical = 10.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = accent,
            fontFamily = DmMonoFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
