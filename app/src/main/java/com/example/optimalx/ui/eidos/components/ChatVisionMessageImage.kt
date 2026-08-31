package com.example.optimalx.ui.eidos.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.optimalx.data.eidos.ChatVisionAttachment
import com.example.optimalx.data.eidos.ChatVisionImageStore

/** Thumbnail on a user bubble when local chat-image bytes exist; otherwise the filename chip. */
@Composable
fun ChatVisionMessageImage(
    attachment: ChatVisionAttachment,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap = remember(attachment.storedName) {
        val file = ChatVisionImageStore.fileFor(context, attachment) ?: return@remember null
        ChatVisionImageStore.decodeThumbnail(file, maxPx = 720)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = attachment.fileName.ifBlank { "Attached image" },
            contentScale = ContentScale.Crop,
            modifier = modifier
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
        ChatVisionAttachmentChip(attachment = attachment, modifier = modifier)
    }
}

@Composable
fun rememberChatVisionThumbnail(attachment: ChatVisionAttachment, maxPx: Int = 256): Bitmap? {
    val context = LocalContext.current
    return remember(attachment.storedName, maxPx) {
        val file = ChatVisionImageStore.fileFor(context, attachment) ?: return@remember null
        ChatVisionImageStore.decodeThumbnail(file, maxPx = maxPx)
    }
}
