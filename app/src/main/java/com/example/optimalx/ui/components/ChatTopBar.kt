package com.example.optimalx.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

/**
 * Shared top bar for all Eidos chat surfaces (EidosChatScreen, WidgetChatActivity, WebPanel sheet).
 *
 * Layout: Back | Title column | History | New Chat | Read Aloud icon | Overflow (⋮)
 */
@Composable
fun ChatTopBar(
    onBack: () -> Unit,
    scopeLabel: String,
    workshopPhaseLabel: String?,
    memoryDepthLabel: String,
    onMemoryDepthClick: () -> Unit,
    hasActiveConversation: Boolean,
    isSending: Boolean,
    onHistoryClick: () -> Unit,
    onNewChatClick: () -> Unit,
    onMoveClick: () -> Unit,
    onStopClick: () -> Unit,
    readAloud: Boolean,
    readAloudMicPassback: Boolean,
    onReadAloudChange: (Boolean) -> Unit,
    onMicPassbackChange: (Boolean) -> Unit,
    readAloudInfoDismissed: Boolean,
    onReadAloudInfoDismissedChange: (Boolean) -> Unit,
    micUseWhisperApi: Boolean,
    hasOpenAiApiKey: Boolean,
    onMicUseWhisperApiChange: (Boolean) -> Unit,
    onOpenChatSettings: () -> Unit = {},
    restrictToolbar: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    var showOverflow by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = colors.textMid,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = "Eidos",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = scopeLabel,
                color = colors.textMid,
                fontFamily = DmSansFamily,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (workshopPhaseLabel != null) {
                Text(
                    text = workshopPhaseLabel,
                    color = colors.accent,
                    fontFamily = DmSansFamily,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        if (!restrictToolbar) {
            IconButton(
                onClick = onHistoryClick,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Conversation history",
                    tint = colors.textMid,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onNewChatClick,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AddComment,
                    contentDescription = "New chat",
                    tint = colors.textMid,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        IconButton(
            onClick = {
                if (!readAloud && !readAloudInfoDismissed) {
                    showInfoDialog = true
                } else {
                    onReadAloudChange(!readAloud)
                }
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = if (readAloud)
                    Icons.AutoMirrored.Filled.VolumeUp
                else
                    Icons.Default.VolumeOff,
                contentDescription = if (readAloud) "Read aloud on" else "Read aloud off",
                tint = if (readAloud) colors.accent else colors.textMid,
                modifier = Modifier.size(20.dp),
            )
        }

        Box {
            IconButton(
                onClick = {
                    onOpenChatSettings()
                    showOverflow = true
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = colors.textMid,
                    modifier = Modifier.size(20.dp),
                )
            }

            ChatOverflowMenu(
                expanded = showOverflow,
                onDismiss = { showOverflow = false },
                hasActiveConversation = hasActiveConversation,
                isSending = isSending,
                onMoveClick = { showOverflow = false; onMoveClick() },
                onStopClick = { showOverflow = false; onStopClick() },
                memoryDepthLabel = memoryDepthLabel,
                onMemoryDepthClick = { onMemoryDepthClick() },
                readAloud = readAloud,
                readAloudMicPassback = readAloudMicPassback,
                onMicPassbackChange = onMicPassbackChange,
                micUseWhisperApi = micUseWhisperApi,
                hasOpenAiApiKey = hasOpenAiApiKey,
                onMicUseWhisperApiChange = onMicUseWhisperApiChange,
            )
        }
    }

    if (showInfoDialog) {
        ReadAloudInfoDialog(
            onEnable = { dismissed ->
                if (dismissed) onReadAloudInfoDismissedChange(true)
                onReadAloudChange(true)
                showInfoDialog = false
            },
            onDismiss = { showInfoDialog = false },
        )
    }
}

@Composable
private fun ChatOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    hasActiveConversation: Boolean,
    isSending: Boolean,
    onMoveClick: () -> Unit,
    onStopClick: () -> Unit,
    memoryDepthLabel: String,
    onMemoryDepthClick: () -> Unit,
    readAloud: Boolean,
    readAloudMicPassback: Boolean,
    onMicPassbackChange: (Boolean) -> Unit,
    micUseWhisperApi: Boolean,
    hasOpenAiApiKey: Boolean,
    onMicUseWhisperApiChange: (Boolean) -> Unit,
) {
    val colors = LocalOptimalXColors.current

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = colors.surface2,
    ) {
        DropdownMenuItem(
            text = { MenuItem("Move conversation here") },
            onClick = onMoveClick,
            enabled = hasActiveConversation,
            colors = MenuDefaults.itemColors(
                textColor = colors.textPrimary,
                disabledTextColor = colors.textDim,
            ),
        )
        DropdownMenuItem(
            text = { MenuItem("Memory: $memoryDepthLabel") },
            onClick = { onMemoryDepthClick() },
            colors = MenuDefaults.itemColors(textColor = colors.accent),
        )
        HorizontalDivider(
            color = colors.border,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        DropdownMenuItem(
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MenuItem("Whisper mic")
                        Switch(
                            checked = micUseWhisperApi,
                            onCheckedChange = {
                                if (it && !hasOpenAiApiKey) return@Switch
                                onMicUseWhisperApiChange(it)
                            },
                            enabled = hasOpenAiApiKey || micUseWhisperApi,
                        )
                    }
                    if (!hasOpenAiApiKey) {
                        Text(
                            text = "Add OpenAI API key in Settings",
                            color = colors.textDim,
                            fontFamily = DmMonoFamily,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            },
            onClick = {
                if (hasOpenAiApiKey || micUseWhisperApi) {
                    onMicUseWhisperApiChange(!micUseWhisperApi)
                }
            },
            colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
        )
        if (readAloud) {
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MenuItem("Pass-back mic")
                        Switch(
                            checked = readAloudMicPassback,
                            onCheckedChange = onMicPassbackChange,
                        )
                    }
                },
                onClick = { onMicPassbackChange(!readAloudMicPassback) },
                colors = MenuDefaults.itemColors(textColor = colors.textPrimary),
            )
        }
        if (isSending) {
            DropdownMenuItem(
                text = { MenuItem("Stop generating", color = colors.accent) },
                onClick = onStopClick,
                colors = MenuDefaults.itemColors(textColor = colors.accent),
            )
        }
    }
}

@Composable
private fun ReadAloudInfoDialog(
    onEnable: (dontShowAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    var dontShowAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface2,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textMid,
        title = {
            Text(
                text = "Read Aloud",
                fontFamily = DmSansFamily,
                fontWeight = FontWeight.Medium,
            )
        },
        text = {
            Column {
                Text(
                    text = "When enabled, Eidos will speak replies aloud automatically " +
                        "using text-to-speech.\n\n" +
                        "Pass-back mic: When \"Pass-back mic\" is on " +
                        "(see \u22ee menu), the microphone will open after Eidos finishes " +
                        "speaking so you can speak your next message hands-free. " +
                        "Tap Send to transcribe and send message.\n\n" +
                        "Hands-free flow: Speak \u2192 Tap Send \u2192 Eidos replies (TTS) " +
                        "\u2192 mic pass-back STT \u2192 tap to send message \u2192 repeat.",
                    fontFamily = DmSansFamily,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .clickable { dontShowAgain = !dontShowAgain },
                ) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = colors.accent,
                            uncheckedColor = colors.textMid,
                            checkmarkColor = colors.background,
                        ),
                    )
                    Text(
                        text = "Don't show this again",
                        color = colors.textMid,
                        fontFamily = DmSansFamily,
                        fontSize = 14.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEnable(dontShowAgain) }) {
                Text("Enable", color = colors.accent, fontFamily = DmSansFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMid, fontFamily = DmSansFamily)
            }
        },
    )
}

@Composable
private fun MenuItem(
    text: String,
    color: androidx.compose.ui.graphics.Color = LocalOptimalXColors.current.textPrimary,
) {
    Text(text = text, color = color, fontFamily = DmSansFamily, fontSize = 15.sp)
}
