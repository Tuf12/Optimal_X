package com.example.optimalx.ui.editor.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.data.eidos.NoteSummaryPolicy
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.ui.theme.OptimalXColors

@Composable
fun NoteSummaryPanel(
    memoryBullets: List<String>,
    contentDigest: String,
    summaryUpdatedAt: Long,
    isViewMode: Boolean,
    onSave: (memoryBullets: List<String>, contentDigest: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    var expanded by remember { mutableStateOf(false) }
    var memoryDraft by remember { mutableStateOf(memoryBulletsToEditableText(memoryBullets)) }
    var contentDraft by remember { mutableStateOf(contentDigest) }
    var summaryDirty by remember { mutableStateOf(false) }
    var lastAppliedSummaryAt by remember { mutableStateOf(0L) }

    LaunchedEffect(memoryBullets, contentDigest, summaryUpdatedAt) {
        if (summaryDirty && summaryUpdatedAt <= lastAppliedSummaryAt) return@LaunchedEffect
        if (summaryDirty && summaryUpdatedAt > lastAppliedSummaryAt) {
            summaryDirty = false
        }
        if (!summaryDirty) {
            memoryDraft = memoryBulletsToEditableText(memoryBullets)
            contentDraft = contentDigest
            lastAppliedSummaryAt = summaryUpdatedAt
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Folder memory & digest",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (summaryDirty) {
                Text(
                    text = "Unsaved",
                    color = colors.accent,
                    fontFamily = DmSansFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse summary" else "Expand summary",
                tint = colors.textDim,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = NoteSummaryPolicy.MEMORY_HEADER,
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                    fontSize = 12.sp,
                )
                Text(
                    text = "Curated facts for Eidos (one per line).",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                OutlinedTextField(
                    value = memoryDraft,
                    onValueChange = {
                        memoryDraft = it
                        summaryDirty = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = isViewMode,
                    minLines = 2,
                    maxLines = 6,
                    placeholder = {
                        Text(
                            "- User prefers bullet lists.",
                            color = colors.textDim,
                            fontFamily = DmSansFamily,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = colors.textPrimary,
                        fontFamily = DmSansFamily,
                        fontSize = 14.sp,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    colors = summaryFieldColors(colors),
                )

                Text(
                    text = NoteSummaryPolicy.CONTENT_HEADER,
                    color = colors.textMid,
                    fontFamily = DmSansFamily,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    text = "Auto-maintained orientation sketch — use search_semantic for note passages; edit only to correct mistakes.",
                    color = colors.textDim,
                    fontFamily = DmSansFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                OutlinedTextField(
                    value = contentDraft,
                    onValueChange = {
                        contentDraft = it
                        summaryDirty = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = isViewMode,
                    minLines = 2,
                    maxLines = 8,
                    placeholder = {
                        Text(
                            "Digest appears when the note grows large.",
                            color = colors.textDim,
                            fontFamily = DmSansFamily,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = colors.textMid,
                        fontFamily = DmSansFamily,
                        fontSize = 13.sp,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    colors = summaryFieldColors(colors),
                )

                if (!isViewMode && summaryDirty) {
                    TextButton(
                        onClick = {
                            onSave(
                                editableTextToMemoryBullets(memoryDraft),
                                contentDraft.trim(),
                            )
                            summaryDirty = false
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            text = "Save summary",
                            color = colors.accent,
                            fontFamily = DmSansFamily,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun summaryFieldColors(colors: OptimalXColors) =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedBorderColor = colors.accent.copy(alpha = 0.6f),
        unfocusedBorderColor = colors.border,
        cursorColor = colors.accent,
    )

internal fun memoryBulletsToEditableText(bullets: List<String>): String =
    bullets.joinToString("\n") { "- $it" }

internal fun editableTextToMemoryBullets(text: String): List<String> =
    text.lines().mapNotNull { line ->
        val trimmed = line.trim().removePrefix("-").trim()
        trimmed.takeIf { it.isNotEmpty() }
    }
