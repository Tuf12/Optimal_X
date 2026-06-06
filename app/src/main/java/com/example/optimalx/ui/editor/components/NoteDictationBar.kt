package com.example.optimalx.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.voice.VoiceSessionState

@Composable
fun NoteDictationBar(
    sessionState: VoiceSessionState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDiscardRequest: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val isListening = sessionState == VoiceSessionState.LISTENING
    val isPaused = sessionState == VoiceSessionState.PAUSED
    val isTranscribing = sessionState == VoiceSessionState.TRANSCRIBING
    val statusLabel = when (sessionState) {
        VoiceSessionState.LISTENING -> "Recording…"
        VoiceSessionState.PAUSED -> "Paused"
        VoiceSessionState.TRANSCRIBING -> "Transcribing…"
        else -> ""
    }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = colors.border)
        Text(
            text = statusLabel,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface2)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            color = colors.accent,
            fontFamily = DmSansFamily,
            fontSize = 12.sp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface2)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onDiscardRequest,
                enabled = !isTranscribing,
                modifier = Modifier.semantics { contentDescription = "Clear recording" },
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = if (isTranscribing) colors.textDim else colors.textPrimary,
                )
            }
            if (isTranscribing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = colors.accent,
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(
                    onClick = if (isPaused) onResume else onPause,
                    modifier = Modifier.semantics {
                        contentDescription = if (isPaused) "Resume recording" else "Pause recording"
                    },
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.Mic else Icons.Default.Pause,
                        contentDescription = null,
                        tint = colors.accent,
                    )
                }
            }
            IconButton(
                onClick = onSend,
                enabled = !isTranscribing,
                modifier = Modifier.semantics { contentDescription = "Transcribe and insert" },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = if (isTranscribing) colors.textDim else colors.accent,
                )
            }
        }
    }
}
