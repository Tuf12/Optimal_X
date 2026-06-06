package com.example.optimalx.ui.workshop.review

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.optimalx.data.model.PendingChangeItem
import com.example.optimalx.data.revision.PENDING_ITEM_STATUS_ACCEPTED
import com.example.optimalx.data.revision.PENDING_ITEM_STATUS_PENDING
import com.example.optimalx.data.revision.PENDING_ITEM_STATUS_REJECTED
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.workshop.components.DiffBlock
import com.example.optimalx.ui.workshop.components.DiffColors

private val AdditionColor = DiffColors.Addition
private val DeletionColor = DiffColors.Deletion
private val HunkHeaderColor = DiffColors.HunkHeader

/**
 * Review queue UI for a workshop project. Lists every [PendingChangeItem] in the
 * currently open [com.example.optimalx.data.model.PendingChangeSet], grouped by file.
 * Each card shows file name + status badge + per-item Accept/Reject. Tapping the
 * header expands the unified diff with monospace formatting and additive/deletion
 * coloring.
 *
 * The toolbar "Accept all" / "Reject all" act on every pending item in the open set.
 * After every accept the workshop editor reloads its in-memory buffer automatically
 * via [com.example.optimalx.ui.workshop.WorkshopEditorViewModel]'s pending-set observer.
 */
@Composable
fun DiffReviewScreen(
    subfolderId: Long,
    onBack: () -> Unit,
    onAllPendingResolved: () -> Unit = onBack,
) {
    val viewModel: DiffReviewViewModel = viewModel(
        key = "diff_review_$subfolderId",
        factory = viewModelFactory {
            initializer {
                val app =
                    this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                DiffReviewViewModel(app as Application, subfolderId)
            }
        }
    )

    val colors = LocalOptimalXColors.current
    val items by viewModel.items.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val busy by viewModel.busy.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var hadPending by remember { mutableStateOf(false) }
    LaunchedEffect(pendingCount, busy) {
        if (pendingCount > 0) hadPending = true
        if (hadPending && pendingCount == 0 && !busy) {
            onAllPendingResolved()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.feedback.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .navigationBarsPadding(),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Review changes",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    text = if (pendingCount > 0) "$pendingCount pending" else "Nothing pending",
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                    fontSize = 11.sp,
                )
            }
            TextButton(
                onClick = { viewModel.rejectAll() },
                enabled = pendingCount > 0 && !busy,
            ) {
                Text(
                    text = "Reject all",
                    color = if (pendingCount > 0 && !busy) DeletionColor else colors.textDim,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
            }
            TextButton(
                onClick = { viewModel.acceptAll() },
                enabled = pendingCount > 0 && !busy,
            ) {
                Text(
                    text = "Accept all",
                    color = if (pendingCount > 0 && !busy) colors.accent else colors.textDim,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (items.isEmpty()) {
                EmptyState(colors = colors)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        ChangeItemCard(
                            item = item,
                            busy = busy,
                            onAccept = { viewModel.acceptItem(item.id) },
                            onReject = { viewModel.rejectItem(item.id) },
                        )
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun EmptyState(colors: com.example.optimalx.ui.theme.OptimalXColors) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No pending changes",
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )
        Text(
            text = "Eidos will queue proposals here while reviewing or updating a project.",
            color = colors.textMid,
            fontFamily = DmSansFamily,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ChangeItemCard(
    item: PendingChangeItem,
    busy: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val pending = item.status == PENDING_ITEM_STATUS_PENDING
    var expanded by remember(item.id) { mutableStateOf(pending) }

    val borderColor = when (item.status) {
        PENDING_ITEM_STATUS_ACCEPTED -> colors.accentBorder
        PENDING_ITEM_STATUS_REJECTED -> colors.border
        else -> colors.border
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(item = item)
            Text(
                text = item.fileName ?: "(unnamed)",
                color = colors.textPrimary,
                fontFamily = DmMonoFamily,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide diff" else "Show diff",
                tint = colors.textMid,
                modifier = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            DiffBlock(diff = item.unifiedDiff)
        }

        if (pending) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onReject, enabled = !busy) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = if (busy) colors.textDim else DeletionColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Reject",
                        color = if (busy) colors.textDim else DeletionColor,
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                TextButton(onClick = onAccept, enabled = !busy) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = if (busy) colors.textDim else colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Accept",
                        color = if (busy) colors.textDim else colors.accent,
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(item: PendingChangeItem) {
    val colors = LocalOptimalXColors.current
    val (label, fg, bg) = when {
        item.status == PENDING_ITEM_STATUS_ACCEPTED -> Triple("ACCEPTED", colors.accent, colors.accentDim)
        item.status == PENDING_ITEM_STATUS_REJECTED -> Triple("REJECTED", DeletionColor, Color(0x1FE07A7A))
        item.isNewFile -> Triple("NEW", AdditionColor, Color(0x1F7BD389))
        else -> Triple("MODIFIED", HunkHeaderColor, Color(0x1F8AB8E8))
    }
    Text(
        text = label,
        color = fg,
        fontFamily = DmMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

