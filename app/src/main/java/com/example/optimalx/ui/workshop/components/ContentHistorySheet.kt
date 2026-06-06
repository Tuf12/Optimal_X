package com.example.optimalx.ui.workshop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.example.optimalx.data.model.ContentCheckpoint
import com.example.optimalx.data.revision.CHECKPOINT_AUTHOR_EIDOS
import com.example.optimalx.data.revision.CHECKPOINT_AUTHOR_SYSTEM
import com.example.optimalx.data.revision.CHECKPOINT_AUTHOR_USER
import com.example.optimalx.data.revision.ContentDiff
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timestampFormatter =
    DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())

/**
 * Modal bottom sheet showing a checkpoint timeline for any content source —
 * workshop file, note, or future surfaces backed by `content_checkpoints`.
 *
 * Each row shows an author badge (BASE / YOU / EIDOS / BUILD), label, timestamp,
 * sequence, and a per-row line-delta vs the working copy. Tap a row to expand
 * its diff inline; tap **Restore** to invoke [onRestore] (the caller is
 * responsible for writing the checkpoint blob back and appending a new
 * `"Restored to seq N"` checkpoint via [com.example.optimalx.data.revision.CheckpointRepository]).
 *
 * @param sourceLabel a short identifier for the source — file name for workshop
 *   files, subfolder name (or similar) for notes — shown under the sheet title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentHistorySheet(
    sourceLabel: String,
    checkpoints: List<ContentCheckpoint>,
    workingCopy: String,
    onRestore: (checkpointId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.sheetBackground,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = "History",
                        color = colors.textPrimary,
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = sourceLabel,
                        color = colors.textMid,
                        fontFamily = DmMonoFamily,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (checkpoints.isEmpty()) {
                Text(
                    text = "No checkpoints yet. Saves and accepted Eidos changes will appear here.",
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .heightIn(max = 560.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(checkpoints, key = { it.id }) { cp ->
                        CheckpointRow(
                            checkpoint = cp,
                            workingCopy = workingCopy,
                            onRestore = { onRestore(cp.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckpointRow(
    checkpoint: ContentCheckpoint,
    workingCopy: String,
    onRestore: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    var expanded by remember(checkpoint.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuthorBadge(checkpoint.author, isBaseline = checkpoint.sequence == 0)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    text = checkpoint.label
                        ?: if (checkpoint.sequence == 0) "Baseline" else "seq ${checkpoint.sequence}",
                    color = colors.textPrimary,
                    fontFamily = DmSansFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${timestampFormatter.format(Instant.ofEpochMilli(checkpoint.createdAt))}  •  seq ${checkpoint.sequence}",
                    color = colors.textMid,
                    fontFamily = DmMonoFamily,
                    fontSize = 11.sp,
                )
            }
            LineDelta(checkpoint.contentBlob, workingCopy)
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide diff" else "Show diff",
                tint = colors.textMid,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(20.dp),
            )
        }

        if (expanded) {
            val diff = remember(checkpoint.id, workingCopy) {
                // checkpoint -> working copy, i.e. "what changed since this checkpoint".
                ContentDiff.unifiedDiff(checkpoint.contentBlob, workingCopy)
            }
            DiffBlock(diff = diff)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRestore) {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Restore",
                        color = colors.accent,
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
private fun AuthorBadge(author: String, isBaseline: Boolean) {
    val colors = LocalOptimalXColors.current
    val (label, fg, bg) = when {
        isBaseline -> Triple("BASE", colors.textMid, colors.border)
        author == CHECKPOINT_AUTHOR_USER -> Triple("YOU", colors.accent, colors.accentDim)
        author == CHECKPOINT_AUTHOR_EIDOS -> Triple("EIDOS", DiffColors.HunkHeader, Color(0x1F8AB8E8))
        author == CHECKPOINT_AUTHOR_SYSTEM -> Triple("BUILD", DiffColors.Addition, Color(0x1F7BD389))
        else -> Triple(author.uppercase(), colors.textMid, colors.border)
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

@Composable
private fun LineDelta(checkpointContent: String, workingCopy: String) {
    val colors = LocalOptimalXColors.current
    if (checkpointContent == workingCopy) {
        Text(
            text = "= current",
            color = colors.textDim,
            fontFamily = DmMonoFamily,
            fontSize = 11.sp,
        )
        return
    }
    val (added, removed) = remember(checkpointContent, workingCopy) {
        countLineDelta(checkpointContent, workingCopy)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) {
            Text(
                text = "+$added",
                color = DiffColors.Addition,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        if (removed > 0) {
            Text(
                text = "-$removed",
                color = DiffColors.Deletion,
                fontFamily = DmMonoFamily,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * Counts net additions and deletions by tallying `+`/`-` markers from
 * [ContentDiff.unifiedDiff]. Lines starting with `+++`/`---` (file headers) and
 * `@@` (hunk headers) are excluded.
 */
private fun countLineDelta(before: String, after: String): Pair<Int, Int> {
    val diff = ContentDiff.unifiedDiff(before, after)
    if (diff.isEmpty()) return 0 to 0
    var added = 0
    var removed = 0
    for (line in diff.split('\n')) {
        if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")) continue
        if (line.startsWith("+")) added++
        else if (line.startsWith("-")) removed++
    }
    return added to removed
}
