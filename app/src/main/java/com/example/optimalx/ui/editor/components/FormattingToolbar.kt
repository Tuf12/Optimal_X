package com.example.optimalx.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun FormattingToolbar(
    editorState: MarkdownNoteEditorState,
    showReadAloud: Boolean = true,
    readAloudSessionActive: Boolean,
    onReadAloudClick: () -> Unit,
    noteMicActive: Boolean = false,
    noteMicEnabled: Boolean = true,
    onNoteMicClick: () -> Unit = {},
    onMoreClick: () -> Unit,
    moreMenuContent: @Composable () -> Unit = {},
    onAfterFormatAction: () -> Unit = {},
    isViewMode: Boolean = false,
    onToggleViewMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrlInput by remember { mutableStateOf("") }

    val headingLevel = editorState.headingLevel
    val isBold = editorState.isBold
    val isItalic = editorState.isItalic
    val isStrike = editorState.isStrike
    val isCode = editorState.isCode
    val isLink = editorState.isLink
    val isBullet = editorState.isBullet
    val isNumbered = editorState.isNumbered

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Add link", fontFamily = DmSansFamily) },
            text = {
                OutlinedTextField(
                    value = linkUrlInput,
                    onValueChange = { linkUrlInput = it },
                    label = { Text("URL") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editorState.apply { text, selection ->
                            NoteMarkdownActions.applyLink(text, selection, linkUrlInput)
                        }
                        showLinkDialog = false
                        linkUrlInput = ""
                        onAfterFormatAction()
                    },
                ) {
                    Text("Add", fontFamily = DmSansFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) {
                    Text("Cancel", fontFamily = DmSansFamily)
                }
            },
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(colors.surface)
            .border(width = 1.dp, color = colors.border),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            if (isViewMode) {
                Text(
                    text = "Reading",
                    color = colors.textDim,
                    fontFamily = DmMonoFamily,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FormatButton(
                        icon = Icons.Default.FormatBold,
                        active = isBold,
                        onClick = {
                            editorState.apply(NoteMarkdownActions::toggleBold)
                            onAfterFormatAction()
                        },
                    )
                    FormatButton(
                        icon = Icons.Default.FormatItalic,
                        active = isItalic,
                        onClick = {
                            editorState.apply(NoteMarkdownActions::toggleItalic)
                            onAfterFormatAction()
                        },
                    )
                    FormatButton(
                        icon = Icons.Default.FormatStrikethrough,
                        active = isStrike,
                        onClick = {
                            editorState.apply(NoteMarkdownActions::toggleStrike)
                            onAfterFormatAction()
                        },
                    )

                    TextButton(
                        onClick = {
                            editorState.apply(NoteMarkdownActions::cycleHeading)
                            onAfterFormatAction()
                        },
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .semantics {
                                contentDescription =
                                    "Heading: ${headingLevel.shortLabel}. Tap to cycle Norm, H1, H2, H3."
                            },
                    ) {
                        Text(
                            text = headingLevel.shortLabel,
                            color = if (headingLevel == NoteHeadingLevel.BODY) {
                                colors.textPrimary
                            } else {
                                colors.accent
                            },
                            fontFamily = DmMonoFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                    }

                    FormatButton(
                        icon = Icons.Default.Link,
                        active = isLink,
                        onClick = { showLinkDialog = true },
                    )
                    FormatButton(
                        icon = Icons.Default.Code,
                        active = isCode,
                        onClick = {
                            editorState.apply(NoteMarkdownActions::toggleInlineCode)
                            onAfterFormatAction()
                        },
                    )

                    FormatButton(
                        icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                        active = isBullet,
                        onClick = {
                            editorState.apply(NoteMarkdownActions::toggleUnorderedList)
                            onAfterFormatAction()
                        },
                    )
                    FormatButton(
                        icon = Icons.Default.FormatListNumbered,
                        active = isNumbered,
                        onClick = {
                            editorState.apply(NoteMarkdownActions::toggleOrderedList)
                            onAfterFormatAction()
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.wrapContentWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onToggleViewMode != null) {
                TextButton(
                    onClick = onToggleViewMode,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Icon(
                        imageVector = if (isViewMode) Icons.Default.Edit else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = if (isViewMode) colors.accent else colors.textPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isViewMode) "Edit" else "View",
                        color = if (isViewMode) colors.accent else colors.textPrimary,
                        fontFamily = DmSansFamily,
                        fontWeight = if (isViewMode) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                }
                VerticalDivider(
                    modifier = Modifier
                        .height(24.dp)
                        .padding(horizontal = 2.dp),
                    color = colors.border,
                )
            }

            if (!isViewMode) {
                IconButton(
                    onClick = onNoteMicClick,
                    enabled = noteMicEnabled,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = if (noteMicActive) "Stop dictation" else "Dictate note" },
                ) {
                    Icon(
                        imageVector = if (noteMicActive) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (noteMicActive) colors.accent else colors.textPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (showReadAloud) {
                IconButton(
                    onClick = onReadAloudClick,
                    modifier = Modifier
                        .size(40.dp)
                        .semantics {
                            contentDescription =
                                if (readAloudSessionActive) "Stop read aloud" else "Read note aloud"
                        },
                ) {
                    Icon(
                        imageVector = if (readAloudSessionActive) Icons.Default.Stop else Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = if (readAloudSessionActive) colors.accent else colors.textPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Box {
                IconButton(onClick = onMoreClick, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                moreMenuContent()
            }

            Spacer(Modifier.width(2.dp))
        }
    }
}

@Composable
private fun FormatButton(
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) colors.accent else colors.textPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}
