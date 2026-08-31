package com.example.optimalx.ui.eidos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationHistorySheet(
    summaries: List<ConversationSummary>,
    directories: List<ConversationDirectoryOption>,
    selectedDirectory: ConversationDirectory,
    onDirectorySelected: (ConversationDirectory) -> Unit,
    parentTargets: List<ConversationLocationTarget>,
    selectedParentId: Long?,
    onParentSelected: (Long) -> Unit,
    onOpenSubfolders: () -> Unit,
    onOpenParentChats: () -> Unit,
    locationTargets: List<ConversationLocationTarget>,
    selectedLocationId: Long?,
    onLocationSelected: (Long) -> Unit,
    contextLabel: String,
    onSelect: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDismiss: () -> Unit,
    /** When true (e.g. Eidos is waiting on the API), show Stop so this sheet isn’t a dead-end over the chat. */
    isRequestInFlight: Boolean = false,
    onStopRequest: () -> Unit = {},
) {
    val colors = LocalOptimalXColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }

    val historyListNestedScroll = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y != 0f && (listState.canScrollBackward || listState.canScrollForward)) {
                    return available.copy(x = 0f)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (available.y != 0f && (listState.canScrollBackward || listState.canScrollForward)) {
                    return available.copy(x = 0f)
                }
                return Velocity.Zero
            }
        }
    }

    val displayed = if (query.isBlank()) {
        summaries
    } else {
        summaries.filter { it.title.contains(query, ignoreCase = true) || it.snippet.contains(query, ignoreCase = true) }
    }

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
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Conversations",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
            )

            Text(
                text = contextLabel,
                color = colors.textDim,
                fontFamily = DmMonoFamily,
                fontSize = 12.sp,
            )

            if (isRequestInFlight) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accentDim)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Reply in progress — stop cancels the current request.",
                        color = colors.textPrimary,
                        fontFamily = DmSansFamily,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    IconButton(onClick = onStopRequest) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = colors.accent,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                directories.forEach { option ->
                    FilterChip(
                        selected = selectedDirectory == option.directory,
                        onClick = { onDirectorySelected(option.directory) },
                        label = {
                            Text(
                                text = option.label,
                                fontFamily = DmSansFamily,
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }

            if (selectedDirectory == ConversationDirectory.PARENT || selectedDirectory == ConversationDirectory.SUBFOLDER) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedDirectory == ConversationDirectory.PARENT,
                        onClick = onOpenParentChats,
                        label = { Text("Parent Chats", fontFamily = DmSansFamily, fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = selectedDirectory == ConversationDirectory.SUBFOLDER,
                        onClick = onOpenSubfolders,
                        label = { Text("Subfolders", fontFamily = DmSansFamily, fontSize = 12.sp) },
                    )
                }

                if (parentTargets.isEmpty()) {
                    Text(
                        text = "No parent folders available.",
                        color = colors.textDim,
                        fontFamily = DmMonoFamily,
                        fontSize = 12.sp,
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(parentTargets, key = { it.id }) { target ->
                            FilterChip(
                                selected = selectedParentId == target.id,
                                onClick = { onParentSelected(target.id) },
                                label = {
                                    Text(
                                        text = target.label,
                                        fontFamily = DmSansFamily,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (selectedDirectory == ConversationDirectory.SUBFOLDER) {
                if (locationTargets.isEmpty()) {
                    Text(
                        text = "No subfolders in selected parent.",
                        color = colors.textDim,
                        fontFamily = DmMonoFamily,
                        fontSize = 12.sp,
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(locationTargets, key = { it.id }) { target ->
                            FilterChip(
                                selected = selectedLocationId == target.id,
                                onClick = { onLocationSelected(target.id) },
                                label = {
                                    Text(
                                        text = target.label,
                                        fontFamily = DmSansFamily,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text("Search…", color = colors.textDim, fontFamily = DmSansFamily, fontSize = 13.sp)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surface2,
                    unfocusedContainerColor = colors.surface2,
                    focusedIndicatorColor = colors.accent,
                    unfocusedIndicatorColor = colors.border,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                ),
            )

            if (displayed.isEmpty()) {
                Text(
                    text = "No conversations yet.",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            DisableSelection {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.nestedScroll(historyListNestedScroll),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(displayed, key = { it.id }) { summary ->
                        val isActive = summary.isActive
                        val shape = RoundedCornerShape(10.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(colors.surface2)
                                .border(
                                    width = if (isActive) 2.dp else 1.dp,
                                    color = if (isActive) colors.accent else colors.border,
                                    shape = shape,
                                )
                                .clickable { onSelect(summary.id); onDismiss() }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = summary.title,
                                color = if (isActive) colors.accent else colors.textPrimary,
                                fontFamily = DmMonoFamily,
                                fontSize = 12.sp,
                                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = { onRename(summary.id, summary.title) },
                                ) {
                                    Text(
                                        text = "Rename",
                                        color = colors.textMid,
                                        fontFamily = DmSansFamily,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            if (summary.snippet.isNotBlank()) {
                                Text(
                                    text = summary.snippet,
                                    color = colors.textDim,
                                    fontFamily = DmSansFamily,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
