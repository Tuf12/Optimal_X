package com.example.optimalx.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.voice.VoiceSessionState

/**
 * Shared composer bar for all Eidos chat surfaces.
 *
 * [usesWhisperBufferedCapture]: pause/clear/send-to-transcribe (PCM buffer).
 * Google streaming STT: tap field or pause mic stops recognition and flushes text for editing.
 */
@Composable
fun ChatComposerBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    onDiscardRecording: () -> Unit,
    onStopClick: () -> Unit,
    sessionState: VoiceSessionState,
    isSending: Boolean,
    isCapturingVoice: Boolean,
    isPaused: Boolean,
    isSpeaking: Boolean,
    liveTranscript: String,
    usesWhisperBufferedCapture: Boolean,
    onTextFieldFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val isListening = sessionState == VoiceSessionState.LISTENING
    val showLiveInField = isCapturingVoice && (usesWhisperBufferedCapture || isListening)
    val displayText = if (showLiveInField) liveTranscript else input
    val fieldDisabledForCapture = usesWhisperBufferedCapture && isCapturingVoice
    val canSend = !isSending && (
        input.isNotBlank() ||
        isCapturingVoice ||
        (usesWhisperBufferedCapture && isPaused)
        )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = { if (!showLiveInField) onInputChange(it) },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp, max = 140.dp)
                .onFocusChanged { if (it.isFocused) onTextFieldFocused() },
            enabled = !isSending && !fieldDisabledForCapture,
            placeholder = {
                val hint = when (sessionState) {
                    VoiceSessionState.LISTENING -> if (usesWhisperBufferedCapture) {
                        "Recording…"
                    } else {
                        "Listening… tap here to edit"
                    }
                    VoiceSessionState.TRANSCRIBING -> "Transcribing…"
                    VoiceSessionState.PAUSED -> "Paused — resume or clear recording"
                    VoiceSessionState.SPEAKING -> "Eidos is speaking…"
                    VoiceSessionState.IDLE -> "Message Eidos…"
                }
                Text(hint, color = colors.textDim, fontFamily = DmSansFamily, fontSize = 14.sp)
            },
            textStyle = TextStyle(
                color = if (showLiveInField) colors.accent else colors.textPrimary,
                fontFamily = DmSansFamily,
                fontSize = 14.sp,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                disabledTextColor = colors.textPrimary,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = if (showLiveInField || isPaused) colors.accent else colors.border,
                disabledBorderColor = if (showLiveInField || isPaused) colors.accent else colors.border,
                focusedContainerColor = colors.surface2,
                unfocusedContainerColor = colors.surface2,
                disabledContainerColor = colors.surface2,
                cursorColor = colors.accent,
            ),
            shape = RoundedCornerShape(12.dp),
            maxLines = 5,
        )

        if (usesWhisperBufferedCapture && isPaused) {
            IconButton(
                onClick = onDiscardRecording,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear recording",
                    tint = colors.accent,
                )
            }
        }

        IconButton(
            onClick = onMicClick,
            enabled = !isSending && sessionState != VoiceSessionState.TRANSCRIBING,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = when {
                    isSpeaking -> Icons.Default.Stop
                    isListening && usesWhisperBufferedCapture -> Icons.Default.Pause
                    isListening -> Icons.Default.Pause
                    isPaused -> Icons.Default.Mic
                    else -> Icons.Default.Mic
                },
                contentDescription = when {
                    isSpeaking -> "Stop speaking"
                    isListening && usesWhisperBufferedCapture -> "Pause recording"
                    isListening -> "Stop listening and edit"
                    isPaused -> "Resume recording"
                    else -> "Voice input"
                },
                tint = when {
                    isCapturingVoice || isSpeaking || isPaused -> colors.accent
                    else -> colors.textMid
                },
            )
        }

        if (isSending) {
            Surface(
                onClick = onStopClick,
                shape = RoundedCornerShape(10.dp),
                color = colors.accent,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop request",
                        tint = colors.background,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            Surface(
                onClick = { if (canSend) onSend() },
                enabled = canSend,
                shape = RoundedCornerShape(10.dp),
                color = if (canSend) colors.accent else colors.surface2,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = when {
                            usesWhisperBufferedCapture && (isCapturingVoice || isPaused) -> {
                                "Transcribe and send"
                            }
                            isListening -> "Send message"
                            else -> "Send"
                        },
                        tint = if (canSend) colors.background else colors.textDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
