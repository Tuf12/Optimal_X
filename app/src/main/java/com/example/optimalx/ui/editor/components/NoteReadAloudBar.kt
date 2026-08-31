package com.example.optimalx.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.optimalx.ui.theme.LocalOptimalXColors

@Composable
fun NoteReadAloudBar(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = colors.border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface2)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onRewind10,
                modifier = Modifier.semantics {
                    contentDescription = "Rewind about ten seconds"
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Replay10,
                    contentDescription = null,
                    tint = colors.textPrimary,
                )
            }
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.semantics {
                    contentDescription = if (isPlaying) "Pause reading" else "Resume reading"
                },
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = colors.accent,
                )
            }
            IconButton(
                onClick = onForward10,
                modifier = Modifier.semantics {
                    contentDescription = "Skip forward about ten seconds"
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Forward10,
                    contentDescription = null,
                    tint = colors.textPrimary,
                )
            }
        }
    }
}
