package com.example.optimalx.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmMonoFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.mohamedrejeb.richeditor.model.RichTextState

enum class NoteFontSize(val sp: TextUnit, val label: String) {
    SMALL(13.sp, "S"),
    MEDIUM(15.sp, "M"),
    LARGE(18.sp, "L"),
    EXTRA_LARGE(22.sp, "XL");

    fun next(): NoteFontSize = entries[(ordinal + 1) % entries.size]
}

@Composable
fun FormattingToolbar(
    richTextState: RichTextState,
    currentFontSize: NoteFontSize,
    onFontSizeCycle: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    showReadAloud: Boolean = true,
    readAloudSessionActive: Boolean,
    onReadAloudClick: () -> Unit,
    noteMicActive: Boolean = false,
    noteMicEnabled: Boolean = true,
    onNoteMicClick: () -> Unit = {},
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val spanStyle = richTextState.currentSpanStyle

    val isBold = spanStyle.fontWeight == FontWeight.Bold
    val isItalic = spanStyle.fontStyle == FontStyle.Italic
    val isUnderline = spanStyle.textDecoration?.contains(TextDecoration.Underline) == true
    val isBullet = richTextState.isUnorderedList
    val isNumbered = richTextState.isOrderedList

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(colors.surface)
            .border(width = 1.dp, color = colors.border),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatButton(
            icon = Icons.Default.FormatBold,
            active = isBold,
            onClick = { richTextState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) },
        )
        FormatButton(
            icon = Icons.Default.FormatItalic,
            active = isItalic,
            onClick = { richTextState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) },
        )
        FormatButton(
            icon = Icons.Default.FormatUnderlined,
            active = isUnderline,
            onClick = { richTextState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) },
        )

        // Font size — cycles through 4 steps
        TextButton(
            onClick = onFontSizeCycle,
            modifier = Modifier.padding(horizontal = 2.dp),
        ) {
            Text(
                text = currentFontSize.label,
                color = colors.textPrimary,
                fontFamily = DmMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
        }

        FormatButton(
            icon = Icons.AutoMirrored.Filled.FormatListBulleted,
            active = isBullet,
            onClick = { richTextState.toggleUnorderedList() },
        )
        FormatButton(
            icon = Icons.Default.FormatListNumbered,
            active = isNumbered,
            onClick = { richTextState.toggleOrderedList() },
        )

        Spacer(Modifier.weight(1f))

        FormatButton(icon = Icons.AutoMirrored.Filled.Undo, active = false, onClick = onUndo)
        FormatButton(icon = Icons.AutoMirrored.Filled.Redo, active = false, onClick = onRedo)

        Spacer(Modifier.width(4.dp))

        Spacer(Modifier.width(2.dp))

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

            Spacer(Modifier.width(2.dp))
        }

        IconButton(onClick = onMoreClick, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More",
                tint = colors.textPrimary,
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.width(4.dp))
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
