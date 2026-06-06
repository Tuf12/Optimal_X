package com.example.optimalx.ui.folders

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

private val shortcutShape = RoundedCornerShape(12.dp)
private val pinShape = RoundedCornerShape(10.dp)

data class PinnedRowSystemSlot(
    val item: PinnedRowItem,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
fun PinnedRow(
    userPins: List<PinnedRowItem.UserPin>,
    onPanelsClick: () -> Unit,
    onDumpEditClick: () -> Unit,
    onWorkshopClick: () -> Unit,
    onQuickNotesClick: () -> Unit,
    onUserPinClick: (PinnedRowItem.UserPin) -> Unit = {},
    onUserPinLongClick: (PinnedRowItem.UserPin) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showPinHint by remember { mutableStateOf(false) }
    var pinPendingUnpin by remember { mutableStateOf<PinnedRowItem.UserPin?>(null) }

    val systemSlots = remember(
        onPanelsClick,
        onDumpEditClick,
        onWorkshopClick,
        onQuickNotesClick,
    ) {
        listOf(
            PinnedRowSystemSlot(PinnedRowItem.SystemPanels, "Panels", Icons.Filled.ViewModule, onPanelsClick),
            PinnedRowSystemSlot(PinnedRowItem.SystemDumpEdit, "DumpEdit", Icons.Filled.EditNote, onDumpEditClick),
            PinnedRowSystemSlot(PinnedRowItem.SystemWorkshop, "Workshop", Icons.Filled.Construction, onWorkshopClick),
            PinnedRowSystemSlot(
                PinnedRowItem.SystemQuickNotes,
                "Quick Notes",
                Icons.AutoMirrored.Filled.StickyNote2,
                onQuickNotesClick,
            ),
        )
    }

    val rowItems = remember(systemSlots, userPins) {
        buildList {
            systemSlots.forEach { add(RowEntry.System(it)) }
            if (userPins.isEmpty()) {
                add(RowEntry.Hint)
            }
            userPins.forEach { add(RowEntry.User(it)) }
        }
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = rowItems,
            key = { entry ->
                when (entry) {
                    is RowEntry.System -> "system_${entry.slot.item}"
                    RowEntry.Hint -> "pin_hint"
                    is RowEntry.User -> "pin_${entry.pin.pinId}"
                }
            },
        ) { entry ->
            when (entry) {
                is RowEntry.System -> SystemShortcutCard(
                    label = entry.slot.label,
                    icon = entry.slot.icon,
                    onClick = entry.slot.onClick,
                )
                RowEntry.Hint -> PinHintButton(onClick = { showPinHint = true })
                is RowEntry.User -> UserPinCard(
                    label = entry.pin.label,
                    onClick = { onUserPinClick(entry.pin) },
                    onLongClick = { pinPendingUnpin = entry.pin },
                )
            }
        }
    }

    if (showPinHint) {
        PinToHomeHintDialog(onDismiss = { showPinHint = false })
    }

    pinPendingUnpin?.let { pin ->
        UnpinFromHomeConfirmDialog(
            label = pin.label,
            onDismiss = { pinPendingUnpin = null },
            onConfirm = {
                onUserPinLongClick(pin)
                pinPendingUnpin = null
            },
        )
    }
}

private sealed class RowEntry {
    data class System(val slot: PinnedRowSystemSlot) : RowEntry()
    data object Hint : RowEntry()
    data class User(val pin: PinnedRowItem.UserPin) : RowEntry()
}

@Composable
private fun SystemShortcutCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current

    Column(
        modifier = modifier
            .widthIn(min = 72.dp, max = 88.dp)
            .clip(shortcutShape)
            .background(colors.surface2)
            .border(1.dp, colors.accent.copy(alpha = 0.35f), shortcutShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = colors.accent,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 13.sp,
        )
    }
}

@Composable
private fun PinHintButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current

    Column(
        modifier = modifier
            .width(56.dp)
            .clip(shortcutShape)
            .background(colors.surface)
            .border(1.dp, colors.border, shortcutShape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Pin shortcut",
            modifier = Modifier.size(22.dp),
            tint = colors.textDim,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Pin",
            color = colors.textDim,
            fontFamily = DmSansFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserPinCard(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current

    Column(
        modifier = modifier
            .widthIn(min = 72.dp, max = 96.dp)
            .clip(pinShape)
            .background(colors.surface)
            .border(1.dp, colors.border, pinShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = colors.textPrimary,
            fontFamily = DmSansFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun UnpinFromHomeConfirmDialog(
    label: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalOptimalXColors.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(16.dp))
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(
                text = "Remove pin?",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Remove \"$label\" from your pinned shortcuts? " +
                    "The folder or panel stays in its original location.",
                color = colors.textMid,
                fontFamily = DmSansFamily,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cancel",
                        color = colors.textMid,
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onConfirm) {
                    Text(
                        text = "Remove",
                        color = colors.accent,
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PinToHomeHintDialog(onDismiss: () -> Unit) {
    val colors = LocalOptimalXColors.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(16.dp))
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(
                text = "Pin a shortcut",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Long-press a folder or panel, then choose Pin to home. " +
                    "Pinned items appear here as shortcuts — they stay in their original location.",
                color = colors.textMid,
                fontFamily = DmSansFamily,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Got it",
                        color = colors.accent,
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
