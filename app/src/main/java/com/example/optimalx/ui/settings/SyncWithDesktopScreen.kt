package com.example.optimalx.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.optimalx.data.sync.SyncConflictDto
import com.example.optimalx.data.sync.WorkshopBackupAllProgress
import com.example.optimalx.data.sync.WorkshopBackupProgress
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.SyneFamily
import java.text.DateFormat
import java.util.Date

private enum class SyncConfirmAction {
    PUSH,
    PULL,
    PULL_FULL,
}

@Composable
fun SyncWithDesktopScreen(
    onBack: () -> Unit,
    viewModel: SyncWithDesktopViewModel = viewModel(),
) {
    BackHandler { onBack() }

    val colors = LocalOptimalXColors.current
    val state by viewModel.uiState.collectAsState()
    var showToken by rememberSaveable { mutableStateOf(false) }
    var pairingUri by rememberSaveable { mutableStateOf("") }
    var pendingSyncAction by remember { mutableStateOf<SyncConfirmAction?>(null) }

    pendingSyncAction?.let { action ->
        SyncConfirmDialog(
            action = action,
            desktopHost = state.host.ifBlank { "desktop" },
            lastSyncAt = state.lastSyncAt,
            onDismiss = { pendingSyncAction = null },
            onConfirm = {
                pendingSyncAction = null
                when (action) {
                    SyncConfirmAction.PUSH -> viewModel.push()
                    SyncConfirmAction.PULL -> viewModel.pull()
                    SyncConfirmAction.PULL_FULL -> viewModel.pullFull()
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .navigationBarsPadding(),
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
                text = "Sync with Desktop",
                color = colors.textPrimary,
                fontFamily = SyneFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Manual Tier 1–2 sync with OptimalX Desktop on your LAN. Push/Pull include conversations, panel state, and revision history. Push also uploads new attachment bytes to the PC; use Sync all attachments for catch-up. Workshop file bytes use a separate Tier 3 sync (per project or bulk below).",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )

            SyncField(
                label = "Desktop host (IP or hostname)",
                value = state.host,
                onValueChange = viewModel::setHost,
                enabled = !state.busy,
            )
            SyncField(
                label = "Port",
                value = state.port,
                onValueChange = viewModel::setPort,
                enabled = !state.busy,
                keyboardType = KeyboardType.Number,
            )
            SyncField(
                label = "Bearer token",
                value = state.token,
                onValueChange = viewModel::setToken,
                enabled = !state.busy,
                isSecure = true,
                reveal = showToken,
                onToggleReveal = { showToken = !showToken },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = "PAIRING URI (OPTIONAL)",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SyncField(
                    label = "optimalx-sync://…",
                    value = pairingUri,
                    onValueChange = { pairingUri = it },
                    enabled = !state.busy,
                )
                TextButton(
                    onClick = { viewModel.applyPairingUri(pairingUri) },
                    enabled = !state.busy && pairingUri.isNotBlank(),
                ) {
                    Text("Apply URI", color = colors.accent, fontFamily = DmSansFamily)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SyncActionButton(
                    label = if (state.busy) "…" else "Test",
                    onClick = viewModel::testConnection,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
                SyncActionButton(
                    label = if (state.busy) "…" else "Push",
                    onClick = { pendingSyncAction = SyncConfirmAction.PUSH },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
                SyncActionButton(
                    label = if (state.busy) "…" else "Pull",
                    onClick = { pendingSyncAction = SyncConfirmAction.PULL },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = "Incremental Pull sends only desktop changes since your last sync. Full pull re-requests the entire Tier 1 snapshot — use it when Pull returns 0 rows but desktop has data.",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 10.sp,
            )
            SyncActionButton(
                label = if (state.busy) "…" else "Full pull",
                onClick = { pendingSyncAction = SyncConfirmAction.PULL_FULL },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                emphasized = true,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "ATTACHMENT FILE SYNC (TIER 3)",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                )
                Text(
                    text = "Upload PDFs, images, and other subfolder attachments to the desktop under optimalx_files/. " +
                        "Panel Workshop project files are excluded — use workshop sync below. " +
                        "Metadata for each attachment is re-sent before bytes upload (e.g. after you removed files on the PC).",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
                SyncActionButton(
                    label = attachmentBackupButtonLabel(state),
                    onClick = viewModel::pushAllAttachments,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    emphasized = true,
                )
                state.attachmentBackupProgress?.let { progress ->
                    Text(
                        text = formatAttachmentBackupProgress(progress),
                        color = colors.accent,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "WORKSHOP FILE SYNC (TIER 3)",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                )
                Text(
                    text = "Upload every Panel Workshop project tree to the desktop under backups/mobile-workshop/. " +
                        "Run Tier 1 Push first so the PC knows each project's globalId. " +
                        "After Pull, sync files from PC in each project drawer.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
                SyncActionButton(
                    label = workshopBackupButtonLabel(state),
                    onClick = viewModel::backupAllWorkshops,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    emphasized = true,
                )
                state.workshopBackupProgress?.let { progress ->
                    Text(
                        text = formatWorkshopBackupProgress(progress),
                        color = colors.accent,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                    )
                }
            }

            if (state.statusMessage != null) {
                Text(
                    text = state.statusMessage.orEmpty(),
                    color = colors.accent,
                    fontFamily = DmMonoFamily,
                    fontSize = 12.sp,
                )
            }
            if (state.errorMessage != null) {
                Text(
                    text = state.errorMessage.orEmpty(),
                    color = colors.accent,
                    fontFamily = DmMonoFamily,
                    fontSize = 12.sp,
                )
            }

            if (state.lastApplied.isNotEmpty() || state.lastSkipped.isNotEmpty()) {
                val applied = state.lastApplied.values.sum()
                val skipped = state.lastSkipped.values.sum()
                Text(
                    text = "Applied $applied, skipped $skipped",
                    color = colors.textMid,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                )
            }

            if (state.conflicts.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "CONFLICTS",
                        color = colors.textDim,
                        fontFamily = DmMonoFamily,
                        fontSize = 11.sp,
                    )
                    state.conflicts.forEach { conflict ->
                        ConflictRow(
                            conflict = conflict,
                            busy = state.busy,
                            onKeepLocal = { viewModel.resolveConflict(conflict, keepLocal = true) },
                            onKeepRemote = { viewModel.resolveConflict(conflict, keepLocal = false) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Device: ${state.deviceId.ifBlank { "—" }}",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
            Text(
                text = "Last sync: ${formatSyncTime(state.lastSyncAt)}",
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ConflictRow(
    conflict: SyncConflictDto,
    busy: Boolean,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface2)
            .padding(10.dp),
    ) {
        Text(
            text = "${conflict.table} · ${conflict.globalId.take(8)}…",
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        if (conflict.reason.isNotBlank()) {
            Text(
                text = conflict.reason,
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
        }
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onKeepLocal, enabled = !busy) {
                Text("Keep local", color = colors.accent, fontFamily = DmSansFamily, fontSize = 12.sp)
            }
            TextButton(onClick = onKeepRemote, enabled = !busy) {
                Text("Keep remote", color = colors.textMid, fontFamily = DmSansFamily, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SyncActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val colors = LocalOptimalXColors.current
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (emphasized) colors.accentDim else colors.surface2)
            .border(
                1.dp,
                if (emphasized) colors.accentBorder else colors.border,
                RoundedCornerShape(12.dp),
            ),
    ) {
        Text(label, color = colors.accent, fontFamily = DmSansFamily, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SyncField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    isSecure: Boolean = false,
    reveal: Boolean = false,
    onToggleReveal: (() -> Unit)? = null,
) {
    val colors = LocalOptimalXColors.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isSecure && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (onToggleReveal != null) {
            {
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        imageVector = if (reveal) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = colors.textDim,
                    )
                }
            }
        } else null,
        label = { Text(label, fontFamily = DmSansFamily) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surface2,
            unfocusedContainerColor = colors.surface2,
            focusedIndicatorColor = colors.accent,
            unfocusedIndicatorColor = colors.border,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.textDim,
            cursorColor = colors.accent,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            disabledTextColor = colors.textDim,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatSyncTime(epochMs: Long): String {
    if (epochMs <= 0L) return "Never"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
}

private fun workshopBackupButtonLabel(state: SyncWithDesktopUiState): String {
    if (!state.busy || state.workshopBackupProgress == null) {
        return if (state.busy) "…" else "Sync all workshop files to PC"
    }
    return "Syncing…"
}

private fun attachmentBackupButtonLabel(state: SyncWithDesktopUiState): String {
    if (!state.busy || state.attachmentBackupProgress == null) {
        return if (state.busy) "…" else "Sync all attachments to PC"
    }
    return "Syncing…"
}

private fun formatAttachmentBackupProgress(progress: WorkshopBackupProgress): String {
    if (progress.totalFiles == 0) return "Scanning attachments…"
    val filePart = "file ${progress.uploadedFiles}/${progress.totalFiles}"
    val pathPart = progress.currentPath?.let { " · $it" }.orEmpty()
    return "$filePart$pathPart"
}

private fun formatWorkshopBackupProgress(progress: WorkshopBackupAllProgress): String {
    if (progress.projectsTotal == 0) return "Scanning projects…"
    val projectLabel = progress.currentProjectName?.let { "\"$it\"" } ?: "project"
    val projectPart = "Project ${progress.projectsCompleted + 1}/${progress.projectsTotal}: $projectLabel"
    if (progress.currentFileTotal == 0) return projectPart
    val filePart = "file ${progress.currentFileUploaded}/${progress.currentFileTotal}"
    val pathPart = progress.currentPath?.let { " · $it" }.orEmpty()
    return "$projectPart — $filePart$pathPart"
}

@Composable
private fun SyncConfirmDialog(
    action: SyncConfirmAction,
    desktopHost: String,
    lastSyncAt: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val isPush = action == SyncConfirmAction.PUSH
    val isFullPull = action == SyncConfirmAction.PULL_FULL
    val title = when {
        isPush -> "Confirm push"
        isFullPull -> "Confirm full pull"
        else -> "Confirm pull"
    }
    val confirmLabel = when {
        isPush -> "Push to desktop"
        isFullPull -> "Full pull from desktop"
        else -> "Pull from desktop"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = title,
                color = colors.textPrimary,
                fontFamily = SyneFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SyncDirectionSchematic(action = action)
                SyncConfirmBody(
                    action = action,
                    desktopHost = desktopHost,
                    lastSyncAt = lastSyncAt,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid, fontFamily = DmSansFamily)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = colors.accent, fontFamily = DmSansFamily, fontWeight = FontWeight.Medium)
            }
        },
    )
}

@Composable
private fun SyncConfirmBody(
    action: SyncConfirmAction,
    desktopHost: String,
    lastSyncAt: Long,
) {
    when (action) {
        SyncConfirmAction.PUSH -> {
            SyncConfirmParagraph(
                "Send changes from this phone to OptimalX Desktop at $desktopHost.",
            )
            SyncConfirmParagraph(
                "Only rows changed on this device since ${formatSyncTime(lastSyncAt)} are uploaded.",
            )
            SyncTier1ScopeList()
            SyncConfirmParagraph(
                "After metadata push, new attachment bytes on this phone upload automatically (Tier 3).",
            )
        }
        SyncConfirmAction.PULL -> {
            SyncConfirmParagraph(
                "Download changes from OptimalX Desktop at $desktopHost onto this phone.",
            )
            SyncConfirmParagraph(
                "Incremental: desktop sends rows with updatedAt or deletedAt after ${formatSyncTime(lastSyncAt)}.",
            )
            SyncTier1ScopeList()
            SyncConfirmParagraph(
                "If nothing arrives, try Full pull below — desktop edits must bump updatedAt.",
            )
            SyncConfirmParagraph(
                "Pull does not download workshop file bytes. After Pull, open each Panel Workshop project " +
                    "and use Sync workshop files from PC (or the banner when files are missing).",
            )
        }
        SyncConfirmAction.PULL_FULL -> {
            SyncConfirmParagraph(
                "Download the complete Tier 1 snapshot from OptimalX Desktop at $desktopHost.",
            )
            SyncConfirmParagraph(
                "Full pull ignores your last sync time and asks the desktop for every syncable row " +
                    "(lastSyncAt sent as 0). Includes Tier 2: conversations, panel state, revision history.",
            )
            SyncTier1ScopeList()
            SyncConfirmParagraph(
                "Rows already on this phone are merged — desktop wins only when its copy is newer or " +
                    "timestamps tie with different content (conflict). This does not delete local-only data.",
            )
            SyncConfirmParagraph(
                "File attachment bytes are downloaded on demand when you open a file in a subfolder (after Tier 1 Pull).",
            )
            SyncConfirmParagraph(
                "Workshop panel files are not included — use Sync workshop files from PC in each project after Pull.",
            )
        }
    }
}

@Composable
private fun SyncConfirmParagraph(text: String, dim: Boolean = true) {
    val colors = LocalOptimalXColors.current
    Text(
        text = text,
        color = if (dim) colors.textDim else colors.textMid,
        fontFamily = DmMonoFamily,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

@Composable
private fun SyncTier1ScopeList() {
    val colors = LocalOptimalXColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface2)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = "SYNC SCOPE (TIERS 1–2)",
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
        )
        listOf(
            "Parent folders & subfolders",
            "Notes (content + tombstones)",
            "File references (metadata)",
            "Home pins",
            "DumpEdit scratch buffer",
            "Eidos conversations & chat messages",
            "Panel state & custom panel assignments",
            "Revision history (checkpoints, pending changes)",
        ).forEach { item ->
            Text(
                text = "· $item",
                color = colors.textMid,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SyncDirectionSchematic(action: SyncConfirmAction) {
    val colors = LocalOptimalXColors.current
    val isPush = action == SyncConfirmAction.PUSH
    val isFullPull = action == SyncConfirmAction.PULL_FULL
    val flowLabel = when {
        isPush -> "DATA FLOW — PUSH"
        isFullPull -> "DATA FLOW — FULL PULL"
        else -> "DATA FLOW — PULL (incremental)"
    }
    val caption = when {
        isPush -> "This device → LAN → desktop database"
        isFullPull -> "Entire desktop Tier 1 snapshot → LAN → this device"
        else -> "Desktop changes since last sync → LAN → this device"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface2)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(vertical = 14.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = flowLabel,
            color = if (isFullPull) colors.accent else colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SyncEndpointNode(
                label = "Phone",
                icon = Icons.Default.PhoneAndroid,
                highlighted = isPush,
            )
            SyncArrowSegment(
                pointingRight = isPush,
                thick = isFullPull,
            )
            SyncEndpointNode(
                label = "Desktop",
                icon = Icons.Default.Computer,
                highlighted = !isPush,
                badge = if (isFullPull) "ALL" else null,
            )
        }
        Text(
            text = caption,
            color = colors.accent,
            fontFamily = DmMonoFamily,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SyncEndpointNode(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    highlighted: Boolean,
    badge: String? = null,
) {
    val colors = LocalOptimalXColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) colors.accentDim else colors.surface)
            .border(
                width = 1.dp,
                color = if (highlighted) colors.accentBorder else colors.border,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(vertical = 10.dp, horizontal = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) colors.accent else colors.textMid,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            color = if (highlighted) colors.textPrimary else colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 12.sp,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
        if (badge != null) {
            Text(
                text = badge,
                color = colors.accent,
                fontFamily = DmMonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.accentDim)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun SyncArrowSegment(pointingRight: Boolean, thick: Boolean = false) {
    val colors = LocalOptimalXColors.current
    val dashCount = if (thick) 5 else 3
    Row(
        modifier = Modifier.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (!pointingRight) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(if (thick) 20.dp else 18.dp),
            )
        }
        repeat(dashCount) {
            Text(
                text = "—",
                color = colors.accent,
                fontFamily = DmMonoFamily,
                fontSize = if (thick) 13.sp else 12.sp,
            )
        }
        if (pointingRight) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(if (thick) 20.dp else 18.dp),
            )
        }
    }
}
