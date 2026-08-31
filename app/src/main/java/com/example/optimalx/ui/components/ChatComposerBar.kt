package com.example.optimalx.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.optimalx.data.eidos.ChatVisionAttachment
import com.example.optimalx.data.eidos.ChatVisionImageStore
import com.example.optimalx.ui.eidos.components.rememberChatVisionThumbnail
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import com.example.optimalx.voice.VoiceSessionState

/**
 * Shared composer bar for all Eidos chat surfaces.
 *
 * [usesWhisperBufferedCapture]: pause/clear/send-to-transcribe (PCM buffer).
 * Google streaming STT: tap field or pause mic stops recognition and flushes text for editing.
 * [imageAttachEnabled]: gallery / camera / paste. Set false to hide attach UI.
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
    imageAttachEnabled: Boolean = true,
    pendingImage: ChatVisionAttachment? = null,
    onAttachImageUri: ((Uri) -> Unit)? = null,
    onClearPendingImage: (() -> Unit)? = null,
    onAttachError: ((String) -> Unit)? = null,
) {
    val colors = LocalOptimalXColors.current
    val context = LocalContext.current
    val isListening = sessionState == VoiceSessionState.LISTENING
    val showLiveInField = isCapturingVoice && (usesWhisperBufferedCapture || isListening)
    val displayText = if (showLiveInField) liveTranscript else input
    val fieldDisabledForCapture = usesWhisperBufferedCapture && isCapturingVoice
    val canSend = !isSending && (
        input.isNotBlank() ||
        pendingImage != null ||
        isCapturingVoice ||
        (usesWhisperBufferedCapture && isPaused)
        )
    val attachEnabled = imageAttachEnabled && onAttachImageUri != null

    var attachMenuExpanded by remember { mutableStateOf(false) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { onAttachImageUri?.invoke(it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) captureUri?.let { onAttachImageUri?.invoke(it) }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchCameraCapture(context) { uri ->
                captureUri = uri
                cameraLauncher.launch(uri)
            }
        } else {
            onAttachError?.invoke("Camera permission is required to take a photo.")
        }
    }

    fun pickGallery() {
        galleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    fun pickCamera() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchCameraCapture(context) { uri ->
                captureUri = uri
                cameraLauncher.launch(uri)
            }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun pasteClipboardImage(): Boolean {
        val uri = ChatVisionImageStore.clipboardImageUri(context)
        if (uri == null) return false
        onAttachImageUri?.invoke(uri)
        return true
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (attachEnabled && pendingImage != null) {
            ChatVisionPendingChip(
                attachment = pendingImage,
                enabled = !isSending,
                onClear = { onClearPendingImage?.invoke() },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (attachEnabled) {
                Box {
                    IconButton(
                        onClick = { attachMenuExpanded = true },
                        enabled = !isSending,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Attach image",
                            tint = if (pendingImage != null) colors.accent else colors.textMid,
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenuExpanded,
                        onDismissRequest = { attachMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Photo library", fontFamily = DmSansFamily) },
                            onClick = {
                                attachMenuExpanded = false
                                pickGallery()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Camera", fontFamily = DmSansFamily) },
                            onClick = {
                                attachMenuExpanded = false
                                pickCamera()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Paste image", fontFamily = DmSansFamily) },
                            onClick = {
                                attachMenuExpanded = false
                                if (!pasteClipboardImage()) {
                                    onAttachError?.invoke("No image on the clipboard.")
                                }
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = displayText,
                onValueChange = { if (!showLiveInField) onInputChange(it) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 140.dp)
                    .onFocusChanged { if (it.isFocused) onTextFieldFocused() }
                    .onPreviewKeyEvent { event ->
                        if (!attachEnabled || isSending) return@onPreviewKeyEvent false
                        val native = event.nativeKeyEvent
                        val isPasteShortcut =
                            event.type == KeyEventType.KeyDown &&
                                event.key == Key.V &&
                                (event.isCtrlPressed || event.isMetaPressed)
                        val isPasteKey =
                            native.keyCode == AndroidKeyEvent.KEYCODE_PASTE &&
                                native.action == AndroidKeyEvent.ACTION_DOWN
                        if (!isPasteShortcut && !isPasteKey) return@onPreviewKeyEvent false
                        pasteClipboardImage()
                    },
                enabled = !isSending && !fieldDisabledForCapture,
                placeholder = {
                    val hint = when {
                        pendingImage != null && sessionState == VoiceSessionState.IDLE -> {
                            "Ask about this image…"
                        }
                        sessionState == VoiceSessionState.LISTENING -> if (usesWhisperBufferedCapture) {
                            "Recording…"
                        } else {
                            "Listening… tap here to edit"
                        }
                        sessionState == VoiceSessionState.TRANSCRIBING -> "Transcribing…"
                        sessionState == VoiceSessionState.PAUSED -> "Paused — resume or clear recording"
                        sessionState == VoiceSessionState.SPEAKING -> "Eidos is speaking…"
                        else -> "Message Eidos…"
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
}

@Composable
private fun ChatVisionPendingChip(
    attachment: ChatVisionAttachment,
    enabled: Boolean,
    onClear: () -> Unit,
) {
    val colors = LocalOptimalXColors.current
    val thumb = rememberChatVisionThumbnail(attachment, maxPx = 128)
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp)
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, colors.accentDim, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Vision this send",
                color = colors.textPrimary,
                fontFamily = DmSansFamily,
                fontSize = 12.sp,
            )
            Text(
                text = attachment.fileName.ifBlank { "Image" },
                color = colors.textMid,
                fontFamily = DmSansFamily,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onClear,
            enabled = enabled,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove image",
                tint = colors.textDim,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun launchCameraCapture(context: android.content.Context, onUri: (Uri) -> Unit) {
    val file = ChatVisionImageStore.createCaptureFile(context)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.exportfileprovider",
        file,
    )
    onUri(uri)
}
